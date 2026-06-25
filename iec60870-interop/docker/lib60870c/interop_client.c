/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * interop_client.c
 *
 * Custom lib60870-C CS104 CLIENT (controlling station) for the iec60870-interop
 * test bench. Drives our Java SERVER (the LIMITED scenario) and any compliant
 * CS104 controlled station through a scripted sequence, printing PASS:/FAIL:
 * markers the Java Testcontainers tests can assert on.
 *
 * Built against the in-tree lib60870-C static lib + headers (see Dockerfile).
 *
 * Logging: uses printf; run under `stdbuf -oL -eL` so log lines stream under
 * `docker logs`. See INTEROP-CONTRACT.md sections 10-11 for the run command and
 * the scripted sequence this implements.
 *
 * Usage:
 *   interop_client [host [port]]
 * Env (override defaults):
 *   INTEROP_HOST, INTEROP_PORT, INTEROP_CA, INTEROP_ACCEPT_IOA, INTEROP_REJECT_IOA
 */

#include "cs104_connection.h"
#include "hal_time.h"
#include "hal_thread.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Contract defaults (INTEROP-CONTRACT.md). */
#define DEF_HOST       "127.0.0.1"
#define DEF_PORT       IEC_60870_5_104_DEFAULT_PORT
#define DEF_CA         1
#define DEF_ACCEPT_IOA 2000
#define DEF_REJECT_IOA 3000
#define READ_IOA       1000 /* single-point monitor neighbour to read */

static int passCount = 0;
static int failCount = 0;

static void
pass(const char* msg) { printf("PASS: %s\n", msg); passCount++; }

static void
fail(const char* msg) { printf("FAIL: %s\n", msg); failCount++; }

/* State observed via the ASDU received handler. */
static volatile bool startdtConfirmed = false;
static volatile bool stopdtConfirmed = false;
static volatile bool sawActCon = false;
static volatile bool sawActTerm = false;
static volatile bool sawData = false;
static volatile bool sawReadData = false;

/* Last command confirmation result. */
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

static void
connectionHandler(void* parameter, CS104_Connection connection, CS104_ConnectionEvent event)
{
    (void) parameter; (void) connection;
    switch (event) {
        case CS104_CONNECTION_OPENED:               printf("CLIENT connection opened\n"); break;
        case CS104_CONNECTION_CLOSED:               printf("CLIENT connection closed\n"); break;
        case CS104_CONNECTION_FAILED:               printf("CLIENT connection failed\n"); break;
        case CS104_CONNECTION_STARTDT_CON_RECEIVED: printf("CLIENT STARTDT_CON\n"); startdtConfirmed = true; break;
        case CS104_CONNECTION_STOPDT_CON_RECEIVED:  printf("CLIENT STOPDT_CON\n");  stopdtConfirmed = true; break;
    }
}

static bool
asduReceivedHandler(void* parameter, int address, CS101_ASDU asdu)
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

    printf("CLIENT RECVD type=%s(%i) cot=%i neg=%i elems=%i ioa0=%i\n",
            TypeID_toString(typeId), typeId, cot, neg ? 1 : 0, n, firstIoa);

    if (cot == CS101_COT_ACTIVATION_CON) {
        sawActCon = true;
        lastActConSeen = true;
        lastActConNegative = neg;
        lastActConType = typeId;
        lastActConIoa = firstIoa;
    }
    else if (cot == CS101_COT_ACTIVATION_TERMINATION) {
        sawActTerm = true;
    }
    else if (cot == CS101_COT_REQUEST) {
        sawReadData = true;
        sawData = true;
    }
    else if (cot >= CS101_COT_INTERROGATED_BY_STATION /* 20 */
            && cot <= CS101_COT_REQUESTED_BY_GROUP_4_COUNTER /* 41 */) {
        sawData = true;
    }

    return true;
}

/* Wait up to timeoutMs for predicate *flag to become true (polled). */
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

int
main(int argc, char** argv)
{
    const char* host = getenv("INTEROP_HOST");
    if (!host) host = DEF_HOST;
    if (argc > 1) host = argv[1];

    int port = DEF_PORT;
    const char* portEnv = getenv("INTEROP_PORT");
    if (portEnv) port = atoi(portEnv);
    if (argc > 2) port = atoi(argv[2]);

    int ca = DEF_CA;
    const char* caEnv = getenv("INTEROP_CA");
    if (caEnv) ca = atoi(caEnv);

    int acceptIoa = DEF_ACCEPT_IOA;
    const char* aEnv = getenv("INTEROP_ACCEPT_IOA");
    if (aEnv) acceptIoa = atoi(aEnv);

    int rejectIoa = DEF_REJECT_IOA;
    const char* rEnv = getenv("INTEROP_REJECT_IOA");
    if (rEnv) rejectIoa = atoi(rEnv);

    printf("INTEROP-CLIENT START host=%s port=%i ca=%i acceptIoa=%i rejectIoa=%i\n",
            host, port, ca, acceptIoa, rejectIoa);

    CS104_Connection con = CS104_Connection_create(host, port);
    CS101_AppLayerParameters alParams = CS104_Connection_getAppLayerParameters(con);
    alParams->originatorAddress = 3;

    CS104_Connection_setConnectionHandler(con, connectionHandler, NULL);
    CS104_Connection_setASDUReceivedHandler(con, asduReceivedHandler, NULL);

    /* 1. connect + STARTDT */
    if (!CS104_Connection_connect(con)) {
        fail("connect");
        printf("INTEROP-CLIENT RESULT pass=%i fail=%i\n", passCount, failCount);
        CS104_Connection_destroy(con);
        return 2;
    }
    pass("connect");

    CS104_Connection_sendStartDT(con);
    if (waitFlag(&startdtConfirmed, 5000))
        pass("STARTDT_CON received");
    else
        fail("STARTDT_CON not received");

    /* 2. station interrogation */
    sawActCon = sawData = sawActTerm = false;
    CS104_Connection_sendInterrogationCommand(con, CS101_COT_ACTIVATION, ca, IEC60870_QOI_STATION);
    Thread_sleep(2500);
    if (sawActCon && sawData)
        pass("station interrogation (ACT_CON + data)");
    else
        fail("station interrogation incomplete");

    /* 3. counter interrogation (general) */
    sawActCon = sawData = sawActTerm = false;
    CS104_Connection_sendCounterInterrogationCommand(con, CS101_COT_ACTIVATION, ca,
            IEC60870_QCC_RQT_GENERAL + IEC60870_QCC_FRZ_READ);
    Thread_sleep(2500);
    if (sawActCon && sawData)
        pass("counter interrogation (ACT_CON + data)");
    else
        fail("counter interrogation incomplete");

    /* 4. clock synchronization */
    resetActConWatch();
    {
        struct sCP56Time2a newTime;
        CP56Time2a_createFromMsTimestamp(&newTime, Hal_getTimeInMs());
        CS104_Connection_sendClockSyncCommand(con, ca, &newTime);
    }
    Thread_sleep(2000);
    if (lastActConSeen && !lastActConNegative)
        pass("clock sync confirmed");
    else
        fail("clock sync not confirmed");

    /* 5. read command on a monitor IOA */
    sawReadData = false;
    CS104_Connection_sendReadCommand(con, ca, READ_IOA);
    Thread_sleep(2000);
    if (sawReadData)
        pass("read command returned data");
    else
        fail("read command returned no data");

    /* 6. command expected to be ACCEPTED (single command ON, direct execute) */
    resetActConWatch();
    {
        InformationObject sc = (InformationObject)
                SingleCommand_create(NULL, acceptIoa, true /*ON*/, false /*direct*/, 0);
        CS104_Connection_sendProcessCommandEx(con, CS101_COT_ACTIVATION, ca, sc);
        InformationObject_destroy(sc);
    }
    if (waitFlag(&lastActConSeen, 3000)) {
        if (!lastActConNegative)
            pass("accept command confirmed (P/N=0)");
        else
            fail("accept command was negatively confirmed (P/N=1)");
    } else {
        fail("accept command: no ACT_CON received");
    }

    /* 7. command expected to be REJECTED (single command ON, direct execute) */
    resetActConWatch();
    {
        InformationObject sc = (InformationObject)
                SingleCommand_create(NULL, rejectIoa, true /*ON*/, false /*direct*/, 0);
        CS104_Connection_sendProcessCommandEx(con, CS101_COT_ACTIVATION, ca, sc);
        InformationObject_destroy(sc);
    }
    if (waitFlag(&lastActConSeen, 3000)) {
        if (lastActConNegative)
            pass("reject command negatively confirmed (P/N=1)");
        else
            fail("reject command was positively confirmed (P/N=0)");
    } else {
        fail("reject command: no ACT_CON received");
    }

    /* 8. idle to allow a TESTFR keepalive, then send an explicit test command */
    printf("CLIENT idling for keepalive...\n");
    Thread_sleep(3000);
    resetActConWatch();
    CS104_Connection_sendTestCommand(con, ca);
    Thread_sleep(1500);
    if (lastActConSeen)
        pass("test command confirmed");
    else
        printf("INFO: test command not confirmed (server may not implement C_TS_NA_1)\n");

    /* 9. STOPDT + close */
    CS104_Connection_sendStopDT(con);
    if (waitFlag(&stopdtConfirmed, 3000))
        pass("STOPDT_CON received");
    else
        fail("STOPDT_CON not received");

    Thread_sleep(500);
    CS104_Connection_destroy(con);

    printf("INTEROP-CLIENT RESULT pass=%i fail=%i\n", passCount, failCount);
    return (failCount == 0) ? 0 : 1;
}
