/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * interop_server.c
 *
 * Custom lib60870-C CS104 SERVER (controlled station) for the iec60870-test-interop
 * test bench. Implements the contract documented in
 * iec60870-test-interop/docker/INTEROP-CONTRACT.md -- the Java interop CLIENT tests
 * assert against the addresses, values, and confirmations defined there.
 *
 * Built against the in-tree lib60870-C static lib + headers (see Dockerfile).
 * lib60870-C is GPLv3; this program links it and is therefore distributed only
 * as a standalone network peer binary inside the interop Docker image.
 *
 * Logging: uses printf; run under `stdbuf -oL -eL` so log lines stream under
 * `docker logs` (printf is fully buffered to a pipe). Stable markers are
 * documented in INTEROP-CONTRACT.md section 9.
 */

#include <stdlib.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <signal.h>

#include "cs104_slave.h"
#include "hal_thread.h"
#include "hal_time.h"
#include "tls_config.h"

/* ---- contract constants (keep in sync with INTEROP-CONTRACT.md) ---- */
#define DEFAULT_PORT 2404
#define DEFAULT_CA   1
#define DEFAULT_TLS_CERT_DIR "/interop-tls"

/* Monitor image: IOA = 1000 + typeBlock*10 + timeVariant */
#define IOA_SP_NA 1000
#define IOA_SP_TB 1001
#define IOA_DP_NA 1010
#define IOA_DP_TB 1011
#define IOA_ST_NA 1020
#define IOA_ST_TB 1021
#define IOA_BO_NA 1030
#define IOA_BO_TB 1031
#define IOA_ME_NA 1040 /* normalized */
#define IOA_ME_TD 1041
#define IOA_ME_NB 1050 /* scaled */
#define IOA_ME_TE 1051
#define IOA_ME_NC 1060 /* short float */
#define IOA_ME_TF 1061
#define IOA_IT_NA 1070 /* integrated totals */
#define IOA_IT_TB 1071

/* Fixed monitor values */
#define VAL_SP        true
#define VAL_DP        IEC60870_DOUBLE_POINT_ON
#define VAL_ST        7
#define VAL_ST_TRANS  false
#define VAL_BO        0x12345678u
#define VAL_NORM      0.5f
#define VAL_SCALED    12345
#define VAL_SHORT     3.14159f
#define VAL_COUNTER   1000

/* Command IOA partitioning */
#define ACCEPT_IOA_LO 2000
#define ACCEPT_IOA_HI 2999
#define REJECT_IOA    3000

/* Return-information monitor targets */
#define RETINFO_SC_IOA 1000 /* single command -> single point */
#define RETINFO_SE_IOA 1060 /* setpoint short -> short float   */

static volatile bool running = true;
static CS104_Slave slave = NULL;
static CS101_AppLayerParameters alParams = NULL;
static int g_ca = DEFAULT_CA;

static void
sigint_handler(int signalId)
{
    (void) signalId;
    running = false;
}

static void
nowTime(CP56Time2a t)
{
    CP56Time2a_createFromMsTimestamp(t, Hal_getTimeInMs());
}

/* -------------------- optional CS104 TLS -------------------- */
static bool
envFlag(const char* name)
{
    const char* value = getenv(name);
    return value != NULL
            && (strcmp(value, "1") == 0
                || strcmp(value, "true") == 0
                || strcmp(value, "TRUE") == 0
                || strcmp(value, "yes") == 0
                || strcmp(value, "on") == 0);
}

static void
tlsPath(char* out, size_t outSize, const char* certDir, const char* filename)
{
    snprintf(out, outSize, "%s/%s", certDir, filename);
}

static bool
requireTlsLoad(const char* label, bool ok)
{
    if (!ok) {
        printf("TLS failed to load %s\n", label);
    }
    return ok;
}

static void
tlsEventHandler(void* parameter, TLSEventLevel eventLevel, int eventCode, const char* message, TLSConnection con)
{
    (void) parameter;
    (void) con;
    printf("TLS event level=%i code=%i message=%s\n", eventLevel, eventCode, message ? message : "");
}

static TLSConfiguration
createServerTlsConfiguration(void)
{
    const char* certDir = getenv("INTEROP_TLS_CERT_DIR");
    if (!certDir) certDir = DEFAULT_TLS_CERT_DIR;

    char ca[256];
    char ownCert[256];
    char ownKey[256];
    char allowedClient[256];
    tlsPath(ca, sizeof(ca), certDir, "ca.pem");
    tlsPath(ownCert, sizeof(ownCert), certDir, "server.pem");
    tlsPath(ownKey, sizeof(ownKey), certDir, "server-key.pem");
    tlsPath(allowedClient, sizeof(allowedClient), certDir, "client.pem");

    TLSConfiguration tlsConfig = TLSConfiguration_create();
    if (tlsConfig == NULL) {
        printf("TLS failed to create configuration\n");
        return NULL;
    }

    TLSConfiguration_setMinTlsVersion(tlsConfig, TLS_VERSION_TLS_1_2);
    TLSConfiguration_setChainValidation(tlsConfig, true);
    TLSConfiguration_setEventHandler(tlsConfig, tlsEventHandler, NULL);
    TLSConfiguration_clearCipherSuiteList(tlsConfig);
    TLSConfiguration_addCipherSuite(tlsConfig, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256);

    bool ok = true;
    ok = requireTlsLoad("server-key.pem", TLSConfiguration_setOwnKeyFromFile(tlsConfig, ownKey, NULL)) && ok;
    ok = requireTlsLoad("server.pem", TLSConfiguration_setOwnCertificateFromFile(tlsConfig, ownCert)) && ok;
    ok = requireTlsLoad("ca.pem", TLSConfiguration_addCACertificateFromFile(tlsConfig, ca)) && ok;
    ok = requireTlsLoad("client.pem", TLSConfiguration_addAllowedCertificateFromFile(tlsConfig, allowedClient)) && ok;

    if (!ok) {
        TLSConfiguration_destroy(tlsConfig);
        return NULL;
    }

    printf("TLS enabled certDir=%s\n", certDir);
    return tlsConfig;
}

/*
 * Send exactly ONE information object as its own non-sequence CS101_ASDU.
 *
 * lib60870-C's CS101_ASDU_addInformationObject silently DROPS any object whose
 * TypeID differs from the ASDU's first object (one ASDU carries a single TypeID
 * for all of its objects). Packing a mixed-type interrogation image into one
 * ASDU therefore collapses on the wire to just the first point. To deliver every
 * distinct-type point at its correct TypeID we emit one ASDU per object, per the
 * interrogation contract (INTEROP-CONTRACT.md section 3): each as a non-sequence
 * ASDU with the supplied response COT.
 *
 * Ownership: this takes the caller-allocated `io`, adds it, sends the ASDU, then
 * destroys both `io` and the ASDU.
 */
static void
sendOnePointAsdu(IMasterConnection con, CS101_CauseOfTransmission cot, InformationObject io)
{
    CS101_ASDU asdu = CS101_ASDU_create(alParams, false, cot, 0, g_ca, false, false);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    IMasterConnection_sendASDU(con, asdu);
    CS101_ASDU_destroy(asdu);
}

/* Emit every non-time monitor point (except integrated totals), ONE ASDU PER
 * POINT, with the supplied response COT. Used for station interrogation and
 * (with a different COT) group 1. Time-tagged points are not included here; they
 * are reported with their non-time TypeID per the CS101 spec by group 2 below. */
static void
sendAllMonitorNonTime(IMasterConnection con, CS101_CauseOfTransmission cot)
{
    sendOnePointAsdu(con, cot,
            (InformationObject) SinglePointInformation_create(NULL, IOA_SP_NA, VAL_SP, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) DoublePointInformation_create(NULL, IOA_DP_NA, VAL_DP, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) StepPositionInformation_create(NULL, IOA_ST_NA, VAL_ST, VAL_ST_TRANS, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) BitString32_create(NULL, IOA_BO_NA, VAL_BO));
    sendOnePointAsdu(con, cot,
            (InformationObject) MeasuredValueNormalized_create(NULL, IOA_ME_NA, VAL_NORM, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) MeasuredValueScaled_create(NULL, IOA_ME_NB, VAL_SCALED, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) MeasuredValueShort_create(NULL, IOA_ME_NC, VAL_SHORT, IEC60870_QUALITY_GOOD));
}

/* Group 2 = time-tagged points, reported (per the CS101 spec) with non-time
 * TypeIDs but at the *time-tagged* IOAs so the client can tell which logical
 * point it is. ONE ASDU PER POINT so every distinct TypeID reaches the client. */
static void
sendGroup2AtTimeIoas(IMasterConnection con, CS101_CauseOfTransmission cot)
{
    sendOnePointAsdu(con, cot,
            (InformationObject) SinglePointInformation_create(NULL, IOA_SP_TB, VAL_SP, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) DoublePointInformation_create(NULL, IOA_DP_TB, VAL_DP, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) StepPositionInformation_create(NULL, IOA_ST_TB, VAL_ST, VAL_ST_TRANS, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) BitString32_create(NULL, IOA_BO_TB, VAL_BO));
    sendOnePointAsdu(con, cot,
            (InformationObject) MeasuredValueNormalized_create(NULL, IOA_ME_TD, VAL_NORM, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) MeasuredValueScaled_create(NULL, IOA_ME_TE, VAL_SCALED, IEC60870_QUALITY_GOOD));
    sendOnePointAsdu(con, cot,
            (InformationObject) MeasuredValueShort_create(NULL, IOA_ME_TF, VAL_SHORT, IEC60870_QUALITY_GOOD));
}

/* -------------------- interrogation -------------------- */
static bool
interrogationHandler(void* parameter, IMasterConnection con, CS101_ASDU asdu, uint8_t qoi)
{
    (void) parameter;

    if (CS101_ASDU_getCA(asdu) != g_ca) {
        CS101_ASDU_setCOT(asdu, CS101_COT_UNKNOWN_CA);
        IMasterConnection_sendASDU(con, asdu);
        return true;
    }

    if (qoi == IEC60870_QOI_STATION) {
        printf("IC station\n");
        IMasterConnection_sendACT_CON(con, asdu, false);
        /* All non-counter monitor points, one ASDU per point (section 3). */
        sendAllMonitorNonTime(con, CS101_COT_INTERROGATED_BY_STATION);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else if (qoi == IEC60870_QOI_GROUP_1) {
        printf("IC group=1\n");
        IMasterConnection_sendACT_CON(con, asdu, false);
        /* Group 1 = all non-time points, one ASDU per point. */
        sendAllMonitorNonTime(con, CS101_COT_INTERROGATED_BY_GROUP_1);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else if (qoi == IEC60870_QOI_GROUP_2) {
        printf("IC group=2\n");
        IMasterConnection_sendACT_CON(con, asdu, false);
        /* Group 2 = time-tagged points (reported via non-time TypeIDs at their
         * time-tagged IOAs), one ASDU per point. */
        sendGroup2AtTimeIoas(con, CS101_COT_INTERROGATED_BY_GROUP_2);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else {
        printf("IC group=%i rejected\n", qoi);
        IMasterConnection_sendACT_CON(con, asdu, true); /* negative */
    }

    return true;
}

/* -------------------- counter interrogation -------------------- */
/* Both integrated totals share TypeID M_IT_NA_1, so the single-ASDU packing
 * limitation does not apply; still, emit one ASDU per total for consistency with
 * the interrogation path. */
static void
sendIntegratedTotals(IMasterConnection con, CS101_CauseOfTransmission cot)
{
    struct sBinaryCounterReading bcr;
    BinaryCounterReading_create(&bcr, VAL_COUNTER, 0, false, false, false);

    sendOnePointAsdu(con, cot, (InformationObject) IntegratedTotals_create(NULL, IOA_IT_NA, &bcr));
    sendOnePointAsdu(con, cot, (InformationObject) IntegratedTotals_create(NULL, IOA_IT_TB, &bcr));
}

static bool
counterInterrogationHandler(void* parameter, IMasterConnection con, CS101_ASDU asdu, QualifierOfCIC qcc)
{
    (void) parameter;

    if (CS101_ASDU_getCA(asdu) != g_ca) {
        CS101_ASDU_setCOT(asdu, CS101_COT_UNKNOWN_CA);
        IMasterConnection_sendASDU(con, asdu);
        return true;
    }

    /* low 6 bits = RQT request qualifier */
    int rqt = qcc & 0x3f;

    if (rqt == IEC60870_QCC_RQT_GENERAL) {
        printf("CI general\n");
        IMasterConnection_sendACT_CON(con, asdu, false);
        sendIntegratedTotals(con, CS101_COT_REQUESTED_BY_GENERAL_COUNTER);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else if (rqt == IEC60870_QCC_RQT_GROUP_1) {
        printf("CI group=1\n");
        IMasterConnection_sendACT_CON(con, asdu, false);
        sendIntegratedTotals(con, CS101_COT_REQUESTED_BY_GROUP_1_COUNTER);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else {
        printf("CI group=%i rejected\n", rqt);
        IMasterConnection_sendACT_CON(con, asdu, true); /* negative */
    }

    return true;
}

/* -------------------- read command -------------------- */
static bool
readHandler(void* parameter, IMasterConnection con, CS101_ASDU asdu, int ioa)
{
    (void) parameter;
    printf("READ ioa=%i\n", ioa);

    if (CS101_ASDU_getCA(asdu) != g_ca) {
        CS101_ASDU_setCOT(asdu, CS101_COT_UNKNOWN_CA);
        IMasterConnection_sendASDU(con, asdu);
        return true;
    }

    CS101_ASDU resp = CS101_ASDU_create(alParams, false, CS101_COT_REQUEST, 0, g_ca, false, false);
    InformationObject io = NULL;
    struct sCP56Time2a t;
    nowTime(&t);

    switch (ioa) {
        case IOA_SP_NA: io = (InformationObject) SinglePointInformation_create(NULL, ioa, VAL_SP, IEC60870_QUALITY_GOOD); break;
        case IOA_SP_TB: io = (InformationObject) SinglePointWithCP56Time2a_create(NULL, ioa, VAL_SP, IEC60870_QUALITY_GOOD, &t); break;
        case IOA_DP_NA: io = (InformationObject) DoublePointInformation_create(NULL, ioa, VAL_DP, IEC60870_QUALITY_GOOD); break;
        case IOA_DP_TB: io = (InformationObject) DoublePointWithCP56Time2a_create(NULL, ioa, VAL_DP, IEC60870_QUALITY_GOOD, &t); break;
        case IOA_ST_NA: io = (InformationObject) StepPositionInformation_create(NULL, ioa, VAL_ST, VAL_ST_TRANS, IEC60870_QUALITY_GOOD); break;
        case IOA_ST_TB: io = (InformationObject) StepPositionWithCP56Time2a_create(NULL, ioa, VAL_ST, VAL_ST_TRANS, IEC60870_QUALITY_GOOD, &t); break;
        case IOA_BO_NA: io = (InformationObject) BitString32_create(NULL, ioa, VAL_BO); break;
        case IOA_BO_TB: io = (InformationObject) Bitstring32WithCP56Time2a_create(NULL, ioa, VAL_BO, &t); break;
        case IOA_ME_NA: io = (InformationObject) MeasuredValueNormalized_create(NULL, ioa, VAL_NORM, IEC60870_QUALITY_GOOD); break;
        case IOA_ME_TD: io = (InformationObject) MeasuredValueNormalizedWithCP56Time2a_create(NULL, ioa, VAL_NORM, IEC60870_QUALITY_GOOD, &t); break;
        case IOA_ME_NB: io = (InformationObject) MeasuredValueScaled_create(NULL, ioa, VAL_SCALED, IEC60870_QUALITY_GOOD); break;
        case IOA_ME_TE: io = (InformationObject) MeasuredValueScaledWithCP56Time2a_create(NULL, ioa, VAL_SCALED, IEC60870_QUALITY_GOOD, &t); break;
        case IOA_ME_NC: io = (InformationObject) MeasuredValueShort_create(NULL, ioa, VAL_SHORT, IEC60870_QUALITY_GOOD); break;
        case IOA_ME_TF: io = (InformationObject) MeasuredValueShortWithCP56Time2a_create(NULL, ioa, VAL_SHORT, IEC60870_QUALITY_GOOD, &t); break;
        case IOA_IT_NA: {
            struct sBinaryCounterReading bcr;
            BinaryCounterReading_create(&bcr, VAL_COUNTER, 0, false, false, false);
            io = (InformationObject) IntegratedTotals_create(NULL, ioa, &bcr);
            break;
        }
        case IOA_IT_TB: {
            struct sBinaryCounterReading bcr;
            BinaryCounterReading_create(&bcr, VAL_COUNTER, 0, false, false, false);
            io = (InformationObject) IntegratedTotalsWithCP56Time2a_create(NULL, ioa, &bcr, &t);
            break;
        }
        default:
            break;
    }

    if (io == NULL) {
        CS101_ASDU_destroy(resp);
        /* Unknown IOA: let the stack report UNKNOWN_IOA via the original ASDU. */
        CS101_ASDU_setCOT(asdu, CS101_COT_UNKNOWN_IOA);
        IMasterConnection_sendASDU(con, asdu);
        return true;
    }

    CS101_ASDU_addInformationObject(resp, io);
    InformationObject_destroy(io);
    IMasterConnection_sendASDU(con, resp);
    CS101_ASDU_destroy(resp);
    return true;
}

/* -------------------- clock sync -------------------- */
static bool
clockSyncHandler(void* parameter, IMasterConnection con, CS101_ASDU asdu, CP56Time2a newTime)
{
    (void) parameter; (void) con; (void) asdu;
    printf("CLOCKSYNC h=%02i:%02i:%02i %02i/%02i/%04i\n",
            CP56Time2a_getHour(newTime), CP56Time2a_getMinute(newTime), CP56Time2a_getSecond(newTime),
            CP56Time2a_getDayOfMonth(newTime), CP56Time2a_getMonth(newTime), CP56Time2a_getYear(newTime) + 2000);
    /* Stack auto-sends ACT_CON. Reflect our wall clock back. */
    CP56Time2a_setFromMsTimestamp(newTime, Hal_getTimeInMs());
    return true;
}

/* -------------------- end of initialization -------------------- */
static void
sendEndOfInit(void)
{
    CS101_ASDU asdu = CS101_ASDU_create(alParams, false, CS101_COT_INITIALIZED, 0, g_ca, false, false);
    InformationObject io = (InformationObject) EndOfInitialization_create(NULL, 0 /* COI: local power on */);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    CS104_Slave_enqueueASDU(slave, asdu);
    CS101_ASDU_destroy(asdu);
    printf("END-OF-INIT sent\n");
}

/* -------------------- return-information helpers -------------------- */
static void
sendReturnInfoSingle(int ioa, bool value)
{
    CS101_ASDU asdu = CS101_ASDU_create(alParams, false, CS101_COT_RETURN_INFO_REMOTE, 0, g_ca, false, false);
    InformationObject io = (InformationObject) SinglePointInformation_create(NULL, ioa, value, IEC60870_QUALITY_GOOD);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    CS104_Slave_enqueueASDU(slave, asdu);
    CS101_ASDU_destroy(asdu);
    printf("RETURN-INFO ioa=%i\n", ioa);
}

static void
sendReturnInfoShort(int ioa, float value)
{
    CS101_ASDU asdu = CS101_ASDU_create(alParams, false, CS101_COT_RETURN_INFO_REMOTE, 0, g_ca, false, false);
    InformationObject io = (InformationObject) MeasuredValueShort_create(NULL, ioa, value, IEC60870_QUALITY_GOOD);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    CS104_Slave_enqueueASDU(slave, asdu);
    CS101_ASDU_destroy(asdu);
    printf("RETURN-INFO ioa=%i\n", ioa);
}

/* -------------------- generic ASDU handler (commands etc.) -------------------- */
/*
 * Classify a control IOA and report a phase string. Returns:
 *   0 = accept, 1 = reject, -1 = unknown IOA.
 */
static int
classifyControlIoa(int ioa)
{
    if (ioa >= ACCEPT_IOA_LO && ioa <= ACCEPT_IOA_HI) return 0;
    if (ioa == REJECT_IOA) return 1;
    return -1;
}

/*
 * Handle one process command type. `isSelect` is the S/E bit. We confirm with
 * ACT_CON (positive for accept, negative for reject), and for select+execute we
 * additionally send ACT_TERM after the execute. The return-information update
 * is enqueued only on an *accepted execute or accepted direct command*.
 */
static bool
handleProcessCommand(IMasterConnection con, CS101_ASDU asdu, const char* typeName,
        int ioa, bool isSelect, bool reflectSingle, bool reflectSingleValue,
        bool reflectShort, float reflectShortValue)
{
    int klass = classifyControlIoa(ioa);
    const char* phase = isSelect ? "SELECT" : (CS101_ASDU_getCOT(asdu) == CS101_COT_ACTIVATION ? "DIRECT" : "EXECUTE");
    /* In CS104 both direct-execute and the execute step of S/E use COT=ACTIVATION;
     * the S/E bit distinguishes select from (direct/execute). Label by S/E. */
    if (isSelect) phase = "SELECT"; else phase = "EXECUTE/DIRECT";

    if (klass == 0) {
        printf("CMD %s ioa=%i ACCEPT %s sel=%i\n", typeName, ioa, phase, isSelect ? 1 : 0);
        CS101_ASDU_setCOT(asdu, CS101_COT_ACTIVATION_CON);
        CS101_ASDU_setNegative(asdu, false);
        IMasterConnection_sendASDU(con, asdu);

        if (!isSelect) {
            /* execute or direct: terminate S/E sequences and emit return info */
            IMasterConnection_sendACT_TERM(con, asdu);
            if (reflectSingle) sendReturnInfoSingle(RETINFO_SC_IOA, reflectSingleValue);
            if (reflectShort)  sendReturnInfoShort(RETINFO_SE_IOA, reflectShortValue);
        }
        return true;
    }
    else if (klass == 1) {
        printf("CMD %s ioa=%i REJECT %s sel=%i\n", typeName, ioa, phase, isSelect ? 1 : 0);
        CS101_ASDU_setCOT(asdu, CS101_COT_ACTIVATION_CON);
        CS101_ASDU_setNegative(asdu, true); /* P/N = 1 */
        IMasterConnection_sendASDU(con, asdu);
        return true;
    }
    else {
        printf("CMD %s ioa=%i UNKNOWN-IOA sel=%i\n", typeName, ioa, isSelect ? 1 : 0);
        CS101_ASDU_setCOT(asdu, CS101_COT_UNKNOWN_IOA);
        IMasterConnection_sendASDU(con, asdu);
        return true;
    }
}

static bool
asduHandler(void* parameter, IMasterConnection con, CS101_ASDU asdu)
{
    (void) parameter;
    int typeId = CS101_ASDU_getTypeID(asdu);

    if (CS101_ASDU_getCA(asdu) != g_ca) {
        CS101_ASDU_setCOT(asdu, CS101_COT_UNKNOWN_CA);
        IMasterConnection_sendASDU(con, asdu);
        return true;
    }

    InformationObject io = CS101_ASDU_getElement(asdu, 0);
    int ioa = io ? InformationObject_getObjectAddress(io) : -1;

    bool handled = false;

    switch (typeId) {
        case C_SC_NA_1: {
            SingleCommand sc = (SingleCommand) io;
            bool sel = sc ? SingleCommand_isSelect(sc) : false;
            bool val = sc ? SingleCommand_getState(sc) : false;
            handled = handleProcessCommand(con, asdu, "C_SC_NA_1", ioa, sel,
                    true, val, false, 0.0f);
            break;
        }
        case C_DC_NA_1: {
            DoubleCommand dc = (DoubleCommand) io;
            bool sel = dc ? DoubleCommand_isSelect(dc) : false;
            handled = handleProcessCommand(con, asdu, "C_DC_NA_1", ioa, sel,
                    false, false, false, 0.0f);
            break;
        }
        case C_RC_NA_1: {
            StepCommand rc = (StepCommand) io;
            bool sel = rc ? StepCommand_isSelect(rc) : false;
            handled = handleProcessCommand(con, asdu, "C_RC_NA_1", ioa, sel,
                    false, false, false, 0.0f);
            break;
        }
        case C_SE_NA_1: {
            SetpointCommandNormalized se = (SetpointCommandNormalized) io;
            bool sel = se ? SetpointCommandNormalized_isSelect(se) : false;
            handled = handleProcessCommand(con, asdu, "C_SE_NA_1", ioa, sel,
                    false, false, false, 0.0f);
            break;
        }
        case C_SE_NB_1: {
            SetpointCommandScaled se = (SetpointCommandScaled) io;
            bool sel = se ? SetpointCommandScaled_isSelect(se) : false;
            handled = handleProcessCommand(con, asdu, "C_SE_NB_1", ioa, sel,
                    false, false, false, 0.0f);
            break;
        }
        case C_SE_NC_1: {
            SetpointCommandShort se = (SetpointCommandShort) io;
            bool sel = se ? SetpointCommandShort_isSelect(se) : false;
            float val = se ? SetpointCommandShort_getValue(se) : 0.0f;
            handled = handleProcessCommand(con, asdu, "C_SE_NC_1", ioa, sel,
                    false, false, true, val);
            break;
        }
        case C_BO_NA_1: {
            /* Bitstring32 command has no S/E bit (always direct). */
            handled = handleProcessCommand(con, asdu, "C_BO_NA_1", ioa, false,
                    false, false, false, 0.0f);
            break;
        }
        case C_TS_NA_1: {
            printf("TEST\n");
            CS101_ASDU_setCOT(asdu, CS101_COT_ACTIVATION_CON);
            CS101_ASDU_setNegative(asdu, false);
            IMasterConnection_sendASDU(con, asdu);
            handled = true;
            break;
        }
        case C_TS_TA_1: {
            printf("TEST\n");
            CS101_ASDU_setCOT(asdu, CS101_COT_ACTIVATION_CON);
            CS101_ASDU_setNegative(asdu, false);
            IMasterConnection_sendASDU(con, asdu);
            handled = true;
            break;
        }
        case C_RP_NA_1: {
            printf("RESET-PROCESS\n");
            CS101_ASDU_setCOT(asdu, CS101_COT_ACTIVATION_CON);
            CS101_ASDU_setNegative(asdu, false);
            IMasterConnection_sendASDU(con, asdu);
            sendEndOfInit();
            handled = true;
            break;
        }
        default:
            handled = false;
            break;
    }

    if (io)
        InformationObject_destroy(io);

    return handled;
}

/* -------------------- connection events -------------------- */
static void
connectionEventHandler(void* parameter, IMasterConnection con, CS104_PeerConnectionEvent event)
{
    (void) parameter; (void) con;
    switch (event) {
        case CS104_CON_EVENT_CONNECTION_OPENED:   printf("CONN opened\n"); break;
        case CS104_CON_EVENT_CONNECTION_CLOSED:   printf("CONN closed\n"); break;
        case CS104_CON_EVENT_ACTIVATED:           printf("CONN activated\n"); break;
        case CS104_CON_EVENT_DEACTIVATED:         printf("CONN deactivated\n"); break;
    }
}

static bool
connectionRequestHandler(void* parameter, const char* ipAddress)
{
    (void) parameter;
    printf("CONN request from %s\n", ipAddress);
    return true;
}

int
main(int argc, char** argv)
{
    (void) argc; (void) argv;
    signal(SIGINT, sigint_handler);

    int port = DEFAULT_PORT;
    const char* portEnv = getenv("INTEROP_PORT");
    if (portEnv) port = atoi(portEnv);

    const char* caEnv = getenv("INTEROP_CA");
    if (caEnv) g_ca = atoi(caEnv);

    bool tlsEnabled = envFlag("INTEROP_TLS");
    TLSConfiguration tlsConfig = NULL;
    if (tlsEnabled) {
        tlsConfig = createServerTlsConfiguration();
        if (tlsConfig == NULL) {
            return 1;
        }
        slave = CS104_Slave_createSecure(50, 50, tlsConfig);
    }
    else {
        slave = CS104_Slave_create(50, 50);
    }

    if (slave == NULL) {
        printf("INTEROP-SERVER FAILED to create slave (tls=%i)\n", tlsEnabled ? 1 : 0);
        if (tlsConfig) TLSConfiguration_destroy(tlsConfig);
        return 1;
    }

    CS104_Slave_setLocalAddress(slave, "0.0.0.0");
    CS104_Slave_setLocalPort(slave, port);
    CS104_Slave_setServerMode(slave, CS104_MODE_SINGLE_REDUNDANCY_GROUP);

    alParams = CS104_Slave_getAppLayerParameters(slave);

    CS104_Slave_setClockSyncHandler(slave, clockSyncHandler, NULL);
    CS104_Slave_setInterrogationHandler(slave, interrogationHandler, NULL);
    CS104_Slave_setCounterInterrogationHandler(slave, counterInterrogationHandler, NULL);
    CS104_Slave_setReadHandler(slave, readHandler, NULL);
    CS104_Slave_setASDUHandler(slave, asduHandler, NULL);
    CS104_Slave_setConnectionRequestHandler(slave, connectionRequestHandler, NULL);
    CS104_Slave_setConnectionEventHandler(slave, connectionEventHandler, NULL);

    CS104_Slave_start(slave);

    if (!CS104_Slave_isRunning(slave)) {
        printf("INTEROP-SERVER FAILED to start on port %i tls=%i\n", port, tlsEnabled ? 1 : 0);
        CS104_Slave_destroy(slave);
        if (tlsConfig) TLSConfiguration_destroy(tlsConfig);
        return 1;
    }

    printf("INTEROP-SERVER READY port=%i ca=%i tls=%i\n", port, g_ca, tlsEnabled ? 1 : 0);

    /* End-of-initialization at startup (queued; delivered once a client is active). */
    sendEndOfInit();

    int periodicValue = VAL_SCALED;

    while (running) {
        Thread_sleep(2000);

        /* Periodic scaled measured value at IOA 1050. */
        CS101_ASDU asdu = CS101_ASDU_create(alParams, false, CS101_COT_PERIODIC, 0, g_ca, false, false);
        InformationObject io = (InformationObject) MeasuredValueScaled_create(NULL, IOA_ME_NB, periodicValue, IEC60870_QUALITY_GOOD);
        CS101_ASDU_addInformationObject(asdu, io);
        InformationObject_destroy(io);
        CS104_Slave_enqueueASDU(slave, asdu);
        CS101_ASDU_destroy(asdu);
        printf("PERIODIC ioa=%i value=%i\n", IOA_ME_NB, periodicValue);
        periodicValue++;
    }

    printf("INTEROP-SERVER stopping\n");
    CS104_Slave_stop(slave);
    CS104_Slave_destroy(slave);
    if (tlsConfig) TLSConfiguration_destroy(tlsConfig);
    Thread_sleep(200);
    return 0;
}
