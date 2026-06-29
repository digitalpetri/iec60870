/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * interop_cs101.c
 *
 * Custom lib60870-C CS101 peer (both roles, both link modes) for the
 * iec60870-test-interop test bench. One binary, selected by two env vars:
 *
 *   INTEROP_CS101_MODE   balanced (default) | unbalanced  -- the FT1.2 link mode
 *   INTEROP_CS101_ROLE   slave (default)    | master      -- the peer's role
 *
 * The four MODE x ROLE combinations are:
 *
 *   mode=balanced role=slave  (default)
 *       A CS101 balanced SLAVE (controlled station / outstation). OUR Java
 *       SerialIec101Client (master) drives it. Implements the same point image
 *       and confirmation contract as the CS104 interop_server, reusing the
 *       INTEROP-CONTRACT point image (see INTEROP-CONTRACT-CS101.md).
 *
 *   mode=balanced role=master
 *       A CS101 balanced MASTER (controlling station). OUR Java
 *       SerialIec101Server (slave) is driven by it. Runs a scripted
 *       interrogation + command + spontaneous sequence and logs PASS:/FAIL:
 *       markers plus a final INTEROP-CS101-MASTER RESULT pass=<n> fail=<n> line,
 *       mirroring the CS104 interop_client.
 *
 *   mode=unbalanced role=slave
 *       A CS101 UNBALANCED secondary station (IEC60870_LINK_LAYER_UNBALANCED).
 *       OUR Java SerialIec101Client (unbalanced master) polls it. Identical
 *       application image and handlers as the balanced slave; only the link
 *       layer (link address, no DIR) and the spontaneous/periodic class queue
 *       differ (periodic data is enqueued as class-2 so the master's class-2
 *       poll drains it; command/interrogation responses go to class-1 and are
 *       drained via the access-demand (ACD) escalation).
 *
 *   mode=unbalanced role=master
 *       A CS101 UNBALANCED primary station (IEC60870_LINK_LAYER_UNBALANCED) that
 *       brings up + polls a single secondary (CS101_Master_addSlave +
 *       CS101_Master_pollSingleSlave). OUR Java SerialIec101Server (unbalanced
 *       slave) is driven by it. Runs the same scripted interrogation + command +
 *       spontaneous sequence and PASS:/FAIL:/RESULT markers as the balanced
 *       master, but on a single-threaded poll loop (the unbalanced primary link
 *       layer is not internally locked, so it must be driven from one thread,
 *       exactly as the lib60870-C cs101_master_unbalanced example does).
 *
 * Wiring: this peer opens the serial device created for it by the container
 * entrypoint (cs101-entrypoint.sh, /dev/ttyCS101 by default), which socat
 * bridges to TCP-LISTEN:2404. See INTEROP-CONTRACT-CS101.md for the topology.
 *
 * Built against the in-tree lib60870-C static lib + headers (see Dockerfile).
 * lib60870-C is GPLv3; this program links it and is therefore distributed only
 * as a standalone serial peer binary inside the interop Docker image.
 *
 * Logging: uses printf; run under `stdbuf -oL -eL` so log lines stream under
 * `docker logs` (printf is fully buffered to a pipe). Stable markers are
 * documented in INTEROP-CONTRACT-CS101.md.
 */

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>

/* hal_serial.h MUST precede cs101_master.h: the master header references the
 * SerialPort type but does not include hal_serial.h itself (the lib60870-C
 * cs101_master_balanced example includes hal_serial.h first for this reason). */
#include "hal_serial.h"
#include "hal_thread.h"
#include "hal_time.h"
#include "cs101_master.h"
#include "cs101_slave.h"

/* ---- contract constants (keep in sync with INTEROP-CONTRACT-CS101.md) ---- */
#define DEFAULT_DEVICE   "/dev/ttyCS101"
#define DEFAULT_BAUD     9600
#define DEFAULT_CA       1   /* common address */
#define DEFAULT_LINKADDR 1   /* FT1.2 link address (length 1) */

/* Wire sizing for the balanced 101 link: COT=1 (no OA), CA=1, IOA=2.
 * Matches the Java side's ProtocolProfile(1, 1, 2, 255). */
#define SIZE_OF_COT 1
#define SIZE_OF_CA  1
#define SIZE_OF_IOA 2

/* Monitor image: IOA = 1000 + typeBlock*10 + timeVariant (reused from the
 * CS104 INTEROP-CONTRACT point image). */
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
static int g_ca = DEFAULT_CA;

static void
sigint_handler(int signalId)
{
    (void) signalId;
    running = false;
}

/* ================================================================= */
/* SLAVE role (controlled station) -- OUR Java client drives this.    */
/* Handlers mirror the CS104 interop_server; only the setup and       */
/* enqueue calls differ (CS101_Slave_* instead of CS104_Slave_*).     */
/* ================================================================= */

static CS101_Slave slave = NULL;
static CS101_AppLayerParameters slaveAlParams = NULL;

static void
nowTime(CP56Time2a t)
{
    CP56Time2a_createFromMsTimestamp(t, Hal_getTimeInMs());
}

/* Send exactly ONE information object as its own non-sequence CS101_ASDU.
 *
 * lib60870-C's CS101_ASDU_addInformationObject silently DROPS any object whose
 * TypeID differs from the ASDU's first object, so the mixed-type interrogation
 * image is emitted one ASDU per point (see INTEROP-CONTRACT section 3). */
static void
sendOnePointAsdu(IMasterConnection con, CS101_CauseOfTransmission cot, InformationObject io)
{
    CS101_ASDU asdu = CS101_ASDU_create(slaveAlParams, false, cot, 0, g_ca, false, false);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    IMasterConnection_sendASDU(con, asdu);
    CS101_ASDU_destroy(asdu);
}

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
        sendAllMonitorNonTime(con, CS101_COT_INTERROGATED_BY_STATION);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else if (qoi == IEC60870_QOI_GROUP_1) {
        printf("IC group=1\n");
        IMasterConnection_sendACT_CON(con, asdu, false);
        sendAllMonitorNonTime(con, CS101_COT_INTERROGATED_BY_GROUP_1);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else if (qoi == IEC60870_QOI_GROUP_2) {
        printf("IC group=2\n");
        IMasterConnection_sendACT_CON(con, asdu, false);
        sendGroup2AtTimeIoas(con, CS101_COT_INTERROGATED_BY_GROUP_2);
        IMasterConnection_sendACT_TERM(con, asdu);
    }
    else {
        printf("IC group=%i rejected\n", qoi);
        IMasterConnection_sendACT_CON(con, asdu, true); /* negative */
    }

    return true;
}

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

    int rqt = qcc & 0x3f; /* low 6 bits = RQT request qualifier */

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

    CS101_ASDU resp = CS101_ASDU_create(slaveAlParams, false, CS101_COT_REQUEST, 0, g_ca, false, false);
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

static void
sendEndOfInit(void)
{
    CS101_ASDU asdu = CS101_ASDU_create(slaveAlParams, false, CS101_COT_INITIALIZED, 0, g_ca, false, false);
    InformationObject io = (InformationObject) EndOfInitialization_create(NULL, 0 /* COI: local power on */);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    CS101_Slave_enqueueUserDataClass1(slave, asdu);
    CS101_ASDU_destroy(asdu);
    printf("END-OF-INIT sent\n");
}

static void
sendReturnInfoSingle(int ioa, bool value)
{
    CS101_ASDU asdu = CS101_ASDU_create(slaveAlParams, false, CS101_COT_RETURN_INFO_REMOTE, 0, g_ca, false, false);
    InformationObject io = (InformationObject) SinglePointInformation_create(NULL, ioa, value, IEC60870_QUALITY_GOOD);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    CS101_Slave_enqueueUserDataClass1(slave, asdu);
    CS101_ASDU_destroy(asdu);
    printf("RETURN-INFO ioa=%i\n", ioa);
}

static void
sendReturnInfoShort(int ioa, float value)
{
    CS101_ASDU asdu = CS101_ASDU_create(slaveAlParams, false, CS101_COT_RETURN_INFO_REMOTE, 0, g_ca, false, false);
    InformationObject io = (InformationObject) MeasuredValueShort_create(NULL, ioa, value, IEC60870_QUALITY_GOOD);
    CS101_ASDU_addInformationObject(asdu, io);
    InformationObject_destroy(io);
    CS101_Slave_enqueueUserDataClass1(slave, asdu);
    CS101_ASDU_destroy(asdu);
    printf("RETURN-INFO ioa=%i\n", ioa);
}

static int
classifyControlIoa(int ioa)
{
    if (ioa >= ACCEPT_IOA_LO && ioa <= ACCEPT_IOA_HI) return 0;
    if (ioa == REJECT_IOA) return 1;
    return -1;
}

static bool
handleProcessCommand(IMasterConnection con, CS101_ASDU asdu, const char* typeName,
        int ioa, bool isSelect, bool reflectSingle, bool reflectSingleValue,
        bool reflectShort, float reflectShortValue)
{
    int klass = classifyControlIoa(ioa);
    const char* phase = isSelect ? "SELECT" : "EXECUTE/DIRECT";

    if (klass == 0) {
        printf("CMD %s ioa=%i ACCEPT %s sel=%i\n", typeName, ioa, phase, isSelect ? 1 : 0);
        CS101_ASDU_setCOT(asdu, CS101_COT_ACTIVATION_CON);
        CS101_ASDU_setNegative(asdu, false);
        IMasterConnection_sendASDU(con, asdu);

        if (!isSelect) {
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

static void
slaveLinkLayerStateChanged(void* parameter, int address, LinkLayerState state)
{
    (void) parameter; (void) address;
    switch (state) {
        case LL_STATE_IDLE:      printf("LINK idle\n"); break;
        case LL_STATE_ERROR:     printf("LINK error\n"); break;
        case LL_STATE_BUSY:      printf("LINK busy\n"); break;
        case LL_STATE_AVAILABLE: printf("LINK available\n"); break;
    }
}

static int
runSlave(SerialPort port, IEC60870_LinkLayerMode mode)
{
    bool unbalanced = (mode == IEC60870_LINK_LAYER_UNBALANCED);
    const char* modeName = unbalanced ? "unbalanced" : "balanced";

    slave = CS101_Slave_create(port, NULL, NULL, mode);

    /* DIR is a balanced-mode concept (controlled station -> DIR=0); the
     * unbalanced secondary uses RES instead, so CS101_Slave_setDIR is a no-op
     * there and is skipped. The link-layer address is the secondary station
     * address the unbalanced master polls (and the balanced peer address). */
    if (!unbalanced) {
        CS101_Slave_setDIR(slave, false);
    }
    CS101_Slave_setLinkLayerAddress(slave, DEFAULT_LINKADDR);
    CS101_Slave_setLinkLayerAddressOtherStation(slave, DEFAULT_LINKADDR);

    slaveAlParams = CS101_Slave_getAppLayerParameters(slave);
    slaveAlParams->sizeOfCOT = SIZE_OF_COT;
    slaveAlParams->sizeOfCA = SIZE_OF_CA;
    slaveAlParams->sizeOfIOA = SIZE_OF_IOA;
    slaveAlParams->originatorAddress = 0;

    LinkLayerParameters llParams = CS101_Slave_getLinkLayerParameters(slave);
    llParams->addressLength = 1;
    llParams->useSingleCharACK = true;
    llParams->timeoutForAck = 1000;
    llParams->timeoutRepeat = 2000;

    CS101_Slave_setClockSyncHandler(slave, clockSyncHandler, NULL);
    CS101_Slave_setInterrogationHandler(slave, interrogationHandler, NULL);
    CS101_Slave_setCounterInterrogationHandler(slave, counterInterrogationHandler, NULL);
    CS101_Slave_setReadHandler(slave, readHandler, NULL);
    CS101_Slave_setASDUHandler(slave, asduHandler, NULL);
    CS101_Slave_setLinkLayerStateChanged(slave, slaveLinkLayerStateChanged, NULL);

    if (!SerialPort_open(port)) {
        printf("INTEROP-CS101-PEER FAILED to open serial port\n");
        CS101_Slave_destroy(slave);
        return 1;
    }

    /* Background thread pumps the FT1.2 + application state machine. For the
     * unbalanced secondary this drives the purely reactive poll responses; for
     * the balanced peer it also drives the link bring-up reaction. */
    CS101_Slave_start(slave);

    printf("INTEROP-CS101-PEER READY role=slave mode=%s ca=%i linkAddr=%i\n",
            modeName, g_ca, DEFAULT_LINKADDR);

    /* End-of-initialization at startup (queued; delivered once the link is up). */
    sendEndOfInit();

    int periodicValue = VAL_SCALED;

    while (running) {
        Thread_sleep(2000);

        /* Periodic scaled measured value at IOA 1050 (COT PERIODIC). In
         * unbalanced mode it is enqueued as class-2 so the master's regular
         * class-2 poll delivers it (cyclic data is class 2); in balanced mode
         * the slave sends spontaneously, so class 1 is used as before. */
        CS101_ASDU asdu = CS101_ASDU_create(slaveAlParams, false, CS101_COT_PERIODIC, 0, g_ca, false, false);
        InformationObject io = (InformationObject) MeasuredValueScaled_create(NULL, IOA_ME_NB, periodicValue, IEC60870_QUALITY_GOOD);
        CS101_ASDU_addInformationObject(asdu, io);
        InformationObject_destroy(io);
        if (unbalanced) {
            CS101_Slave_enqueueUserDataClass2(slave, asdu);
        } else {
            CS101_Slave_enqueueUserDataClass1(slave, asdu);
        }
        CS101_ASDU_destroy(asdu);
        printf("PERIODIC ioa=%i value=%i\n", IOA_ME_NB, periodicValue);
        periodicValue++;
    }

    printf("INTEROP-CS101-PEER stopping (slave)\n");
    CS101_Slave_stop(slave);
    CS101_Slave_destroy(slave);
    Thread_sleep(200);
    return 0;
}

/* ================================================================= */
/* MASTER role (controlling station) -- drives OUR Java server/slave. */
/* Scripted interrogation + command + spontaneous, like interop_client. */
/* ================================================================= */

static int passCount = 0;
static int failCount = 0;

static void
pass(const char* msg) { printf("PASS: %s\n", msg); passCount++; }

static void
fail(const char* msg) { printf("FAIL: %s\n", msg); failCount++; }

static volatile bool linkAvailable = false;
static volatile bool sawData = false;
static volatile bool sawCounterData = false;
static volatile bool sawReadData = false;
static volatile bool sawSpontaneous = false;

static volatile bool lastActConSeen = false;
static volatile bool lastActConNegative = false;
static volatile int  lastActConType = -1;
static volatile int  lastActConIoa = -1;

static void
resetActConWatch(void)
{
    lastActConSeen = false;
    lastActConNegative = false;
    lastActConType = -1;
    lastActConIoa = -1;
}

static bool
masterAsduReceivedHandler(void* parameter, int address, CS101_ASDU asdu)
{
    (void) parameter; (void) address;

    int typeId = CS101_ASDU_getTypeID(asdu);
    int cot = CS101_ASDU_getCOT(asdu);
    bool neg = CS101_ASDU_isNegative(asdu);
    int n = CS101_ASDU_getNumberOfElements(asdu);

    int firstIoa = -1;
    if (n > 0) {
        InformationObject io = CS101_ASDU_getElement(asdu, 0);
        if (io) {
            firstIoa = InformationObject_getObjectAddress(io);
            InformationObject_destroy(io);
        }
    }

    printf("MASTER RECVD type=%s(%i) cot=%i neg=%i elems=%i ioa0=%i\n",
            TypeID_toString(typeId), typeId, cot, neg ? 1 : 0, n, firstIoa);

    if (cot == CS101_COT_ACTIVATION_CON) {
        lastActConSeen = true;
        lastActConNegative = neg;
        lastActConType = typeId;
        lastActConIoa = firstIoa;
    }
    else if (cot == CS101_COT_SPONTANEOUS || cot == CS101_COT_PERIODIC) {
        sawSpontaneous = true;
    }
    else if (cot >= CS101_COT_REQUESTED_BY_GENERAL_COUNTER
            && cot <= CS101_COT_REQUESTED_BY_GROUP_4_COUNTER) {
        sawCounterData = true;
        sawData = true;
    }
    else if (cot >= CS101_COT_INTERROGATED_BY_STATION /* 20 */
            && cot <= CS101_COT_INTERROGATED_BY_GROUP_16 /* 36 */) {
        sawData = true;
    }
    else if (cot == CS101_COT_REQUEST) {
        sawReadData = true;
        sawData = true;
    }

    return true;
}

static void
masterLinkLayerStateChanged(void* parameter, int address, LinkLayerState state)
{
    (void) parameter; (void) address;
    switch (state) {
        case LL_STATE_IDLE:      printf("LINK idle\n"); break;
        case LL_STATE_ERROR:     printf("LINK error\n"); linkAvailable = false; break;
        case LL_STATE_BUSY:      printf("LINK busy\n"); break;
        case LL_STATE_AVAILABLE: printf("LINK available\n"); linkAvailable = true; break;
    }
}

/* Wait up to timeoutMs for *flag to become true (polled). */
static bool
waitFlag(volatile bool* flag, int timeoutMs)
{
    int waited = 0;
    while (!*flag && waited < timeoutMs) {
        Thread_sleep(50);
        waited += 50;
    }
    return *flag;
}

static int
runMaster(SerialPort port)
{
    int acceptIoa = ACCEPT_IOA_LO;
    int rejectIoa = REJECT_IOA;
    const char* aEnv = getenv("INTEROP_ACCEPT_IOA");
    if (aEnv) acceptIoa = atoi(aEnv);
    const char* rEnv = getenv("INTEROP_REJECT_IOA");
    if (rEnv) rejectIoa = atoi(rEnv);

    CS101_Master master = CS101_Master_createEx(port, NULL, NULL, IEC60870_LINK_LAYER_BALANCED, 100);

    /* Controlling station: DIR=1 on outgoing frames. */
    CS101_Master_setDIR(master, true);
    CS101_Master_setOwnAddress(master, DEFAULT_LINKADDR);
    CS101_Master_useSlaveAddress(master, DEFAULT_LINKADDR);

    CS101_AppLayerParameters alParams = CS101_Master_getAppLayerParameters(master);
    alParams->sizeOfCOT = SIZE_OF_COT;
    alParams->sizeOfCA = SIZE_OF_CA;
    alParams->sizeOfIOA = SIZE_OF_IOA;
    alParams->originatorAddress = 0;

    LinkLayerParameters llParams = CS101_Master_getLinkLayerParameters(master);
    llParams->addressLength = 1;
    llParams->useSingleCharACK = true;
    llParams->timeoutForAck = 1000;
    llParams->timeoutRepeat = 2000;

    CS101_Master_setASDUReceivedHandler(master, masterAsduReceivedHandler, NULL);
    CS101_Master_setLinkLayerStateChanged(master, masterLinkLayerStateChanged, NULL);

    if (!SerialPort_open(port)) {
        printf("INTEROP-CS101-PEER FAILED to open serial port\n");
        CS101_Master_destroy(master);
        return 1;
    }

    /* Background thread pumps the FT1.2 + application state machine; this also
     * drives the balanced link-reset bring-up and retries until the peer (our
     * Java slave) responds, so the script tolerates startup ordering. */
    CS101_Master_start(master);

    printf("INTEROP-CS101-PEER READY role=master mode=balanced ca=%i linkAddr=%i acceptIoa=%i rejectIoa=%i\n",
            g_ca, DEFAULT_LINKADDR, acceptIoa, rejectIoa);

    /* 1. Wait for the balanced link to come up (peer must be reading the PTY). */
    if (waitFlag(&linkAvailable, 30000))
        pass("link available");
    else
        fail("link did not become available");

    /* 2. Station interrogation -> expect ACT_CON + data. */
    sawData = false;
    resetActConWatch();
    CS101_Master_sendInterrogationCommand(master, CS101_COT_ACTIVATION, g_ca, IEC60870_QOI_STATION);
    Thread_sleep(3000);
    if (lastActConSeen && sawData)
        pass("station interrogation (ACT_CON + data)");
    else
        fail("station interrogation incomplete");

    /* 3. Counter interrogation -> expect ACT_CON + integrated totals. */
    sawCounterData = false;
    resetActConWatch();
    CS101_Master_sendCounterInterrogationCommand(master, CS101_COT_ACTIVATION, g_ca,
            IEC60870_QCC_RQT_GENERAL + IEC60870_QCC_FRZ_READ);
    Thread_sleep(3000);
    if (lastActConSeen && sawCounterData)
        pass("counter interrogation (ACT_CON + data)");
    else
        fail("counter interrogation incomplete");

    /* 4. Read command on a monitor IOA -> expect COT REQUEST data. */
    sawReadData = false;
    CS101_Master_sendReadCommand(master, g_ca, IOA_SP_NA);
    Thread_sleep(2500);
    if (sawReadData)
        pass("read command returned data");
    else
        fail("read command returned no data");

    /* 5. Command expected to be ACCEPTED (single command ON, direct execute). */
    resetActConWatch();
    {
        InformationObject sc = (InformationObject)
                SingleCommand_create(NULL, acceptIoa, true /*ON*/, false /*direct*/, 0);
        CS101_Master_sendProcessCommand(master, CS101_COT_ACTIVATION, g_ca, sc);
        InformationObject_destroy(sc);
    }
    if (waitFlag(&lastActConSeen, 5000)) {
        if (!lastActConNegative)
            pass("accept command confirmed (P/N=0)");
        else
            fail("accept command was negatively confirmed (P/N=1)");
    } else {
        fail("accept command: no ACT_CON received");
    }

    /* 6. Command expected to be REJECTED (single command ON, direct execute). */
    resetActConWatch();
    {
        InformationObject sc = (InformationObject)
                SingleCommand_create(NULL, rejectIoa, true /*ON*/, false /*direct*/, 0);
        CS101_Master_sendProcessCommand(master, CS101_COT_ACTIVATION, g_ca, sc);
        InformationObject_destroy(sc);
    }
    if (waitFlag(&lastActConSeen, 5000)) {
        if (lastActConNegative)
            pass("reject command negatively confirmed (P/N=1)");
        else
            fail("reject command was positively confirmed (P/N=0)");
    } else {
        fail("reject command: no ACT_CON received");
    }

    /* 7. Spontaneous / periodic update from the slave (contract section 8). */
    if (waitFlag(&sawSpontaneous, 8000))
        pass("spontaneous data observed");
    else
        fail("no spontaneous data observed");

    Thread_sleep(500);
    CS101_Master_stop(master);
    CS101_Master_destroy(master);

    printf("INTEROP-CS101-MASTER RESULT pass=%i fail=%i\n", passCount, failCount);
    return (failCount == 0) ? 0 : 1;
}

/* ================================================================= */
/* UNBALANCED MASTER role -- drives OUR Java unbalanced server/slave. */
/*                                                                    */
/* Same scripted interrogation + command + spontaneous sequence and   */
/* the same PASS:/FAIL:/RESULT markers as runMaster(), but driven on a */
/* single thread: the unbalanced primary link layer in lib60870-C is  */
/* NOT internally locked, so CS101_Master_run (RX + per-slave state    */
/* machine) and CS101_Master_pollSingleSlave (keep a class-2 poll      */
/* pending) must be called from one loop -- exactly as the lib60870-C  */
/* cs101_master_unbalanced example does. The single registered slave   */
/* is brought up automatically by CS101_Master_run; its access-demand  */
/* (ACD) bit auto-escalates the master to a class-1 poll, so the Java   */
/* slave's class-1 (interrogation/command response) data is drained.   */
/* ================================================================= */

/* Script phases for the unbalanced master loop. */
enum UnbalancedPhase {
    UM_WAIT_LINK,
    UM_INTERROGATE,
    UM_COUNTER,
    UM_READ,
    UM_ACCEPT,
    UM_REJECT,
    UM_SPONT,
    UM_DONE
};

static int
runUnbalancedMaster(SerialPort port)
{
    int acceptIoa = ACCEPT_IOA_LO;
    int rejectIoa = REJECT_IOA;
    const char* aEnv = getenv("INTEROP_ACCEPT_IOA");
    if (aEnv) acceptIoa = atoi(aEnv);
    const char* rEnv = getenv("INTEROP_REJECT_IOA");
    if (rEnv) rejectIoa = atoi(rEnv);

    int slaveAddr = DEFAULT_LINKADDR; /* the secondary station the master polls */

    /* Unbalanced primary: no message queue (that is balanced-only), so the
     * plain CS101_Master_create is used rather than CS101_Master_createEx. */
    CS101_Master master = CS101_Master_create(port, NULL, NULL, IEC60870_LINK_LAYER_UNBALANCED);

    CS101_AppLayerParameters alParams = CS101_Master_getAppLayerParameters(master);
    alParams->sizeOfCOT = SIZE_OF_COT;
    alParams->sizeOfCA = SIZE_OF_CA;
    alParams->sizeOfIOA = SIZE_OF_IOA;
    alParams->originatorAddress = 0;

    LinkLayerParameters llParams = CS101_Master_getLinkLayerParameters(master);
    llParams->addressLength = 1;
    llParams->useSingleCharACK = true;
    llParams->timeoutForAck = 1000;
    llParams->timeoutRepeat = 2000;

    CS101_Master_setASDUReceivedHandler(master, masterAsduReceivedHandler, NULL);
    CS101_Master_setLinkLayerStateChanged(master, masterLinkLayerStateChanged, NULL);

    /* Register the single secondary and direct all command-direction sends at it. */
    CS101_Master_addSlave(master, slaveAddr);
    CS101_Master_useSlaveAddress(master, slaveAddr);

    if (!SerialPort_open(port)) {
        printf("INTEROP-CS101-PEER FAILED to open serial port\n");
        CS101_Master_destroy(master);
        return 1;
    }

    printf("INTEROP-CS101-PEER READY role=master mode=unbalanced ca=%i slaveAddr=%i acceptIoa=%i rejectIoa=%i\n",
            g_ca, slaveAddr, acceptIoa, rejectIoa);

    enum UnbalancedPhase phase = UM_WAIT_LINK;
    bool entered = false;            /* false until the current phase's one-time action has run */
    uint64_t phaseDeadline = 0;
    uint64_t nextPoll = 0;
    uint64_t overallDeadline = Hal_getTimeInMs() + 90000;

    /* Steady class-2 poll cadence. The poll is issued AFTER the script step (so
     * the freshly cleared requestClass2 flag does not block command sends), and
     * a queued user-data command (FC3) takes priority over the poll in the
     * link-layer state machine, so commands are never starved. */
    const uint64_t pollPeriodMs = 250;

    while (running && phase != UM_DONE) {
        /* Pump RX + the per-slave state machine. This also brings the single
         * registered secondary up automatically (request-status -> reset ->
         * available) and reports the AVAILABLE transition that sets
         * linkAvailable via masterLinkLayerStateChanged. */
        CS101_Master_run(master);

        uint64_t now = Hal_getTimeInMs();
        if (now > overallDeadline) {
            fail("overall unbalanced master script timeout");
            phase = UM_DONE;
            break;
        }

        switch (phase) {
            case UM_WAIT_LINK:
                if (!entered) { entered = true; phaseDeadline = now + 30000; }
                if (linkAvailable) {
                    pass("link available");
                    phase = UM_INTERROGATE; entered = false;
                } else if (now > phaseDeadline) {
                    fail("link did not become available");
                    phase = UM_INTERROGATE; entered = false;
                }
                break;

            case UM_INTERROGATE:
                /* Send once on entry; the response (ACT_CON + per-point data +
                 * ACT_TERM) is buffered by the slave and drained by the class-2
                 * poll below (with class-1 ACD escalation handled by the lib). */
                if (!entered) {
                    entered = true;
                    sawData = false;
                    resetActConWatch();
                    CS101_Master_useSlaveAddress(master, slaveAddr);
                    CS101_Master_sendInterrogationCommand(master, CS101_COT_ACTIVATION, g_ca, IEC60870_QOI_STATION);
                    phaseDeadline = now + 20000;
                }
                if (lastActConSeen && sawData) {
                    pass("station interrogation (ACT_CON + data)");
                    phase = UM_COUNTER; entered = false;
                } else if (now > phaseDeadline) {
                    fail("station interrogation incomplete");
                    phase = UM_COUNTER; entered = false;
                }
                break;

            case UM_COUNTER:
                if (!entered) {
                    entered = true;
                    sawCounterData = false;
                    resetActConWatch();
                    CS101_Master_useSlaveAddress(master, slaveAddr);
                    CS101_Master_sendCounterInterrogationCommand(master, CS101_COT_ACTIVATION, g_ca,
                            IEC60870_QCC_RQT_GENERAL + IEC60870_QCC_FRZ_READ);
                    phaseDeadline = now + 20000;
                }
                if (lastActConSeen && sawCounterData) {
                    pass("counter interrogation (ACT_CON + data)");
                    phase = UM_READ; entered = false;
                } else if (now > phaseDeadline) {
                    fail("counter interrogation incomplete");
                    phase = UM_READ; entered = false;
                }
                break;

            case UM_READ:
                if (!entered) {
                    entered = true;
                    sawReadData = false;
                    CS101_Master_useSlaveAddress(master, slaveAddr);
                    CS101_Master_sendReadCommand(master, g_ca, IOA_SP_NA);
                    phaseDeadline = now + 15000;
                }
                if (sawReadData) {
                    pass("read command returned data");
                    phase = UM_ACCEPT; entered = false;
                } else if (now > phaseDeadline) {
                    fail("read command returned no data");
                    phase = UM_ACCEPT; entered = false;
                }
                break;

            case UM_ACCEPT:
                if (!entered) {
                    entered = true;
                    resetActConWatch();
                    InformationObject sc = (InformationObject)
                            SingleCommand_create(NULL, acceptIoa, true /*ON*/, false /*direct*/, 0);
                    CS101_Master_useSlaveAddress(master, slaveAddr);
                    CS101_Master_sendProcessCommand(master, CS101_COT_ACTIVATION, g_ca, sc);
                    InformationObject_destroy(sc);
                    phaseDeadline = now + 15000;
                }
                if (lastActConSeen) {
                    if (!lastActConNegative)
                        pass("accept command confirmed (P/N=0)");
                    else
                        fail("accept command was negatively confirmed (P/N=1)");
                    phase = UM_REJECT; entered = false;
                } else if (now > phaseDeadline) {
                    fail("accept command: no ACT_CON received");
                    phase = UM_REJECT; entered = false;
                }
                break;

            case UM_REJECT:
                if (!entered) {
                    entered = true;
                    resetActConWatch();
                    InformationObject sc = (InformationObject)
                            SingleCommand_create(NULL, rejectIoa, true /*ON*/, false /*direct*/, 0);
                    CS101_Master_useSlaveAddress(master, slaveAddr);
                    CS101_Master_sendProcessCommand(master, CS101_COT_ACTIVATION, g_ca, sc);
                    InformationObject_destroy(sc);
                    phaseDeadline = now + 15000;
                }
                if (lastActConSeen) {
                    if (lastActConNegative)
                        pass("reject command negatively confirmed (P/N=1)");
                    else
                        fail("reject command was positively confirmed (P/N=0)");
                    phase = UM_SPONT; entered = false;
                } else if (now > phaseDeadline) {
                    fail("reject command: no ACT_CON received");
                    phase = UM_SPONT; entered = false;
                }
                break;

            case UM_SPONT:
                if (!entered) { entered = true; phaseDeadline = now + 20000; }
                if (sawSpontaneous) {
                    pass("spontaneous data observed");
                    phase = UM_DONE; entered = false;
                } else if (now > phaseDeadline) {
                    fail("no spontaneous data observed");
                    phase = UM_DONE; entered = false;
                }
                break;

            case UM_DONE:
                break;
        }

        /* Re-arm a class-2 poll on the cadence (after the script step). */
        now = Hal_getTimeInMs();
        if (now >= nextPoll) {
            CS101_Master_pollSingleSlave(master, slaveAddr);
            nextPoll = now + pollPeriodMs;
        }

        Thread_sleep(5);
    }

    Thread_sleep(200);
    CS101_Master_destroy(master);

    printf("INTEROP-CS101-MASTER RESULT pass=%i fail=%i\n", passCount, failCount);
    return (failCount == 0) ? 0 : 1;
}

/* ================================================================= */

int
main(int argc, char** argv)
{
    (void) argc; (void) argv;
    signal(SIGINT, sigint_handler);

    const char* device = getenv("INTEROP_CS101_DEVICE");
    if (!device) device = DEFAULT_DEVICE;

    int baud = DEFAULT_BAUD;
    const char* baudEnv = getenv("INTEROP_CS101_BAUD");
    if (baudEnv) baud = atoi(baudEnv);

    const char* caEnv = getenv("INTEROP_CA");
    if (caEnv) g_ca = atoi(caEnv);

    const char* role = getenv("INTEROP_CS101_ROLE");
    if (!role) role = "slave";

    const char* mode = getenv("INTEROP_CS101_MODE");
    if (!mode) mode = "balanced";
    IEC60870_LinkLayerMode llMode =
            (strcmp(mode, "unbalanced") == 0) ? IEC60870_LINK_LAYER_UNBALANCED
                                              : IEC60870_LINK_LAYER_BALANCED;

    printf("INTEROP-CS101-PEER START role=%s mode=%s device=%s baud=%i ca=%i\n",
            role, mode, device, baud, g_ca);

    /* 8E1 serial framing as required by FT1.2. */
    SerialPort port = SerialPort_create(device, baud, 8, 'E', 1);

    int rc;
    if (llMode == IEC60870_LINK_LAYER_UNBALANCED) {
        if (strcmp(role, "master") == 0) {
            rc = runUnbalancedMaster(port);
        } else {
            rc = runSlave(port, IEC60870_LINK_LAYER_UNBALANCED);
        }
    } else {
        if (strcmp(role, "master") == 0) {
            rc = runMaster(port);
        } else {
            rc = runSlave(port, IEC60870_LINK_LAYER_BALANCED);
        }
    }

    SerialPort_close(port);
    SerialPort_destroy(port);
    return rc;
}
