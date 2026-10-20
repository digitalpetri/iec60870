package com.digitalpetri.iec60870.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.RequestInProgressException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.FixedTestBitPattern;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameter;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameterActivation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotals;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec60870.asdu.object.ParameterActivation;
import com.digitalpetri.iec60870.asdu.object.ParameterScaled;
import com.digitalpetri.iec60870.asdu.object.ParameterShortFloat;
import com.digitalpetri.iec60870.asdu.object.ResetProcessCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.TimePolicy;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Correlation of counter interrogation, test, reset process, and parameter loading in {@link
 * DefaultIec60870Client}, driven by a {@link FakeSession}.
 */
class CorrelatedServicesTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final OriginatorAddress OWN_OA = OriginatorAddress.of(3);
  private static final InformationObjectAddress IOA_0 = InformationObjectAddress.of(0);
  private static final PointAddress PARAMETER = PointAddress.of(1, 4001);

  private final ManualScheduler clock = new ManualScheduler();
  private final List<ClientEvent> events = new ArrayList<>();
  private final AtomicReference<@Nullable FakeSession> sessionRef = new AtomicReference<>();
  private DefaultIec60870Client client = newClient(ClientConfig.builder());

  @AfterEach
  void tearDown() {
    client.close();
  }

  // --- Counter interrogation ------------------------------------------------------------------

  @Test
  void counterReadCompletesOnTerminationWithTheCounters() {
    client.connect();
    CompletionStage<CounterInterrogationResult> stage = client.counterInterrogateAsync(STATION);

    Asdu request = lastSent();
    assertEquals(AsduType.C_CI_NA_1, request.type());
    assertEquals(Cause.ACTIVATION, request.cause());
    assertEquals(OWN_OA, request.originatorAddress());
    assertEquals(
        List.of(new CounterInterrogationCommand(IOA_0, qcc(5, FreezeMode.READ))),
        request.objects());

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 1070, 1071));
    assertFalse(stage.toCompletableFuture().isDone());
    deliver(reply(request, Cause.ACTIVATION_TERMINATION, false));

    CounterInterrogationResult result = join(stage);
    assertEquals(STATION, result.station());
    assertEquals(qcc(5, FreezeMode.READ), result.qualifier());
    assertEquals(2, result.objects().size());
    assertEquals(2, result.pointValues().size());
    assertEquals(PointAddress.of(1, 1070), result.pointValues().get(0).address());
    assertEquals(0, client.pendingRequestCount());

    long updates =
        events.stream()
            .filter(
                e ->
                    e instanceof ClientEvent.PointUpdated u
                        && u.address().equals(PointAddress.of(1, 1070)))
            .count();
    assertEquals(1, updates, "collected counters are still published as PointUpdated");
  }

  @Test
  void counterResultResolvesTimeTagsWithTheClientPolicy() {
    TimePolicy berlin = TimePolicy.of(ZoneId.of("Europe/Berlin"));
    client.close();
    client = newClient(ClientConfig.builder().timePolicy(berlin));
    client.connect();
    CompletionStage<CounterInterrogationResult> stage = client.counterInterrogateAsync(STATION);
    Asdu request = lastSent();

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    deliver(reply(request, Cause.ACTIVATION_TERMINATION, false));

    assertEquals(berlin, join(stage).timePolicy());
  }

  @Test
  void counterReadCollectsOnlyCountersWithTheRequestedCauseAfterConfirmation() {
    client.connect();
    CompletionStage<CounterInterrogationResult> stage =
        client.counterInterrogateAsync(STATION, qcc(1, FreezeMode.READ));
    Asdu request = lastSent();

    // Before ACT_CON, nothing counts.
    deliver(counters(Cause.REQUESTED_BY_GROUP_1_COUNTER, 1));
    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    // Spontaneous counters, another group, the general cause, and GI responses stay out.
    deliver(counters(Cause.SPONTANEOUS, 2));
    deliver(counters(Cause.REQUESTED_BY_GROUP_2_COUNTER, 3));
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 4));
    deliver(monitor(Cause.INTERROGATED_BY_STATION, 5));
    deliver(monitor(Cause.REQUESTED_BY_GROUP_1_COUNTER, 6));
    deliver(counters(Cause.REQUESTED_BY_GROUP_1_COUNTER, 7));
    deliver(reply(request, Cause.ACTIVATION_TERMINATION, false));

    assertEquals(List.of(7L), addresses(join(stage).objects()));
  }

  @Test
  void freezeAndResetCompleteOnConfirmationWithoutCounters() {
    client.connect();
    for (FreezeMode freeze :
        List.of(FreezeMode.FREEZE_NO_RESET, FreezeMode.FREEZE_WITH_RESET, FreezeMode.RESET)) {
      CompletionStage<CounterInterrogationResult> stage =
          client.counterInterrogateAsync(STATION, qcc(5, freeze));

      deliver(reply(lastSent(), Cause.ACTIVATION_CONFIRMATION, false));

      CounterInterrogationResult result = join(stage);
      assertEquals(qcc(5, freeze), result.qualifier());
      assertTrue(result.objects().isEmpty());
      assertEquals(0, client.pendingRequestCount());
    }
  }

  @Test
  void trailingFreezeTerminationAndDataDoNotAffectTheNextRead() {
    client.connect();
    CompletionStage<CounterInterrogationResult> freeze =
        client.counterInterrogateAsync(STATION, qcc(5, FreezeMode.FREEZE_NO_RESET));
    Asdu freezeRequest = lastSent();
    deliver(reply(freezeRequest, Cause.ACTIVATION_CONFIRMATION, false));
    join(freeze);

    CompletionStage<CounterInterrogationResult> read = client.counterInterrogateAsync(STATION);
    Asdu readRequest = lastSent();

    // Some stations follow a freeze with data and ACT_TERM anyway.
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 1));
    deliver(reply(freezeRequest, Cause.ACTIVATION_TERMINATION, false));
    assertFalse(read.toCompletableFuture().isDone(), "the freeze ACT_TERM must not end the read");

    deliver(reply(readRequest, Cause.ACTIVATION_CONFIRMATION, false));
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 2));
    deliver(reply(readRequest, Cause.ACTIVATION_TERMINATION, false));

    assertEquals(List.of(2L), addresses(join(read).objects()));
  }

  @Test
  void counterConfirmationsMustEchoTheRequest() {
    client.connect();
    CompletionStage<CounterInterrogationResult> stage =
        client.counterInterrogateAsync(STATION, qcc(5, FreezeMode.FREEZE_NO_RESET));
    Asdu request = lastSent();

    // Another QCC, a nonzero IOA, another originator, the test flag, and another station.
    deliver(
        withObjects(
            reply(request, Cause.ACTIVATION_CONFIRMATION, false),
            List.of(new CounterInterrogationCommand(IOA_0, qcc(5, FreezeMode.RESET)))));
    deliver(
        withObjects(
            reply(request, Cause.ACTIVATION_CONFIRMATION, false),
            List.of(
                new CounterInterrogationCommand(
                    InformationObjectAddress.of(1), qcc(5, FreezeMode.FREEZE_NO_RESET)))));
    deliver(withOriginator(reply(request, Cause.ACTIVATION_CONFIRMATION, true), 9));
    deliver(withTest(reply(request, Cause.ACTIVATION_CONFIRMATION, true)));
    deliver(withStation(reply(request, Cause.ACTIVATION_CONFIRMATION, true), 2));
    assertFalse(stage.toCompletableFuture().isDone());

    // OA 0 means no specific originator and still matches.
    deliver(withOriginator(reply(request, Cause.ACTIVATION_CONFIRMATION, false), 0));
    assertTrue(join(stage).objects().isEmpty());
  }

  @Test
  void negativeCounterConfirmationFails() {
    client.connect();
    CompletionStage<CounterInterrogationResult> stage = client.counterInterrogateAsync(STATION);
    Asdu negative = reply(lastSent(), Cause.ACTIVATION_CONFIRMATION, true);

    deliver(negative);

    NegativeConfirmationException e = failure(stage, NegativeConfirmationException.class);
    assertEquals(Optional.of(negative), e.asdu());
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void unknownCauseWithoutNegativeFlagFailsCounterInterrogation() {
    client.connect();
    CompletionStage<CounterInterrogationResult> stage = client.counterInterrogateAsync(STATION);

    // lib60870-C fixtures answer an unknown CA with COT 46 and P/N left at 0.
    deliver(reply(lastSent(), Cause.UNKNOWN_COMMON_ADDRESS, false));

    NegativeConfirmationException e = failure(stage, NegativeConfirmationException.class);
    assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, e.cause());
  }

  @Test
  void secondCounterInterrogationOfTheSameStationIsRejected() {
    client.connect();
    CompletionStage<CounterInterrogationResult> first = client.counterInterrogateAsync(STATION);
    Asdu request = lastSent();

    CompletionStage<CounterInterrogationResult> second =
        client.counterInterrogateAsync(STATION, qcc(1, FreezeMode.FREEZE_NO_RESET));

    failure(second, RequestInProgressException.class);
    assertEquals(1, session().sentAsdus().size(), "a rejected request sends nothing");
    assertEquals(1, client.pendingRequestCount());

    // Another station is independent.
    client.counterInterrogateAsync(CommonAddress.of(2));
    assertEquals(2, client.pendingRequestCount());

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    deliver(reply(request, Cause.ACTIVATION_TERMINATION, false));
    join(first);
  }

  @Test
  void interrogationAndCounterInterrogationInterleave() {
    client.connect();
    CompletionStage<InterrogationResult> gi = client.interrogateAsync(STATION);
    Asdu giRequest = lastSent();
    CompletionStage<CounterInterrogationResult> ci = client.counterInterrogateAsync(STATION);
    Asdu ciRequest = lastSent();
    assertEquals(2, client.pendingRequestCount());

    deliver(reply(giRequest, Cause.ACTIVATION_CONFIRMATION, false));
    deliver(reply(ciRequest, Cause.ACTIVATION_CONFIRMATION, false));
    deliver(monitor(Cause.INTERROGATED_BY_STATION, 10));
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 20));
    deliver(monitor(Cause.INTERROGATED_BY_STATION, 11));
    deliver(reply(ciRequest, Cause.ACTIVATION_TERMINATION, false));
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 21));
    deliver(reply(giRequest, Cause.ACTIVATION_TERMINATION, false));

    assertEquals(List.of(10L, 11L), addresses(join(gi).objects()));
    assertEquals(List.of(20L), addresses(join(ci).objects()));
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void counterReadIsBoundedByMaxInterrogationResponseObjects() {
    client.close();
    client = newClient(ClientConfig.builder().maxInterrogationResponseObjects(2));
    client.connect();
    CompletionStage<CounterInterrogationResult> stage = client.counterInterrogateAsync(STATION);

    deliver(reply(lastSent(), Cause.ACTIVATION_CONFIRMATION, false));
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 1, 2));
    assertFalse(stage.toCompletableFuture().isDone());
    deliver(counters(Cause.REQUESTED_BY_GENERAL_COUNTER, 3));

    Iec60870Exception e = failure(stage, Iec60870Exception.class);
    assertSame(Iec60870Exception.class, e.getClass());
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void counterReadUsesRequestTimeoutAndFreezeUsesCommandTimeout() {
    client.close();
    client =
        newClient(
            ClientConfig.builder()
                .commandTimeout(Duration.ofMillis(100))
                .requestTimeout(Duration.ofMillis(1000)));
    client.connect();
    CompletionStage<CounterInterrogationResult> read = client.counterInterrogateAsync(STATION);
    CompletionStage<CounterInterrogationResult> freeze =
        client.counterInterrogateAsync(CommonAddress.of(2), qcc(5, FreezeMode.FREEZE_NO_RESET));

    clock.advance(100, TimeUnit.MILLISECONDS);
    failure(freeze, ProtocolTimeoutException.class);
    assertFalse(read.toCompletableFuture().isDone());

    clock.advance(900, TimeUnit.MILLISECONDS);
    failure(read, ProtocolTimeoutException.class);
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void counterReadRequiresADefinedRequestQualifier() {
    client.connect();
    assertThrows(
        IllegalArgumentException.class,
        () -> client.counterInterrogateAsync(STATION, qcc(0, FreezeMode.READ)));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.counterInterrogateAsync(STATION, qcc(6, FreezeMode.READ)));
    assertTrue(session().sentAsdus().isEmpty());
  }

  // --- Test command -----------------------------------------------------------------------------

  @Test
  void testSendsTimedTestCommandAndMatchesTheSequenceCounter() {
    client.connect();
    CompletionStage<TestResult> stage = client.testAsync(STATION);
    Asdu request = lastSent();
    assertEquals(AsduType.C_TS_TA_1, request.type());
    TestCommandWithCp56Time sent = (TestCommandWithCp56Time) request.objects().get(0);
    assertEquals(IOA_0, sent.address());

    // Another counter is not this request's echo.
    var otherTsc =
        new TestCommandWithCp56Time(
            IOA_0, UShort.valueOf(sent.testSequenceCounter().intValue() + 1), sent.time());
    deliver(withObjects(reply(request, Cause.ACTIVATION_CONFIRMATION, false), List.of(otherTsc)));
    assertFalse(stage.toCompletableFuture().isDone());

    // A station that re-encodes the time still answers this request.
    var reencoded =
        new TestCommandWithCp56Time(
            IOA_0,
            sent.testSequenceCounter(),
            Cp56Time2a.from(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
    Asdu confirmation =
        withObjects(reply(request, Cause.ACTIVATION_CONFIRMATION, false), List.of(reencoded));
    deliver(confirmation);

    TestResult result = join(stage);
    assertEquals(STATION, result.station());
    assertEquals(confirmation, result.confirmation());
    assertFalse(result.roundTripTime().isNegative());
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void testSequenceCounterIncrementsAndWraps() {
    client.connect();
    int first = -1;
    for (int i = 0; i <= 0x10000; i++) {
      CompletionStage<TestResult> stage = client.testAsync(STATION);
      Asdu request = lastSent();
      int tsc =
          ((TestCommandWithCp56Time) request.objects().get(0)).testSequenceCounter().intValue();
      if (i == 0) {
        first = tsc;
      } else {
        int expected = (first + i) & 0xFFFF;
        if (tsc != expected) {
          assertEquals(expected, tsc, "test sequence counter at iteration " + i);
        }
      }
      deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
      join(stage);
    }
  }

  @Test
  void untimedTestSendsTheFixedPattern() {
    client.connect();
    CompletionStage<TestResult> stage = client.testAsync(STATION, AsduType.C_TS_NA_1);
    Asdu request = lastSent();
    assertEquals(AsduType.C_TS_NA_1, request.type());
    assertEquals(
        List.of(new TestCommand(IOA_0, FixedTestBitPattern.of(0x55AA))), request.objects());

    deliver(
        withObjects(
            reply(request, Cause.ACTIVATION_CONFIRMATION, false),
            List.of(new TestCommand(IOA_0, FixedTestBitPattern.of(0x1234)))));
    assertFalse(stage.toCompletableFuture().isDone(), "the pattern must be echoed");

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    assertFalse(join(stage).roundTripTime().isNegative());
  }

  @Test
  void testRejectsOtherTypes() {
    client.connect();
    assertThrows(
        IllegalArgumentException.class, () -> client.testAsync(STATION, AsduType.C_RP_NA_1));
    assertTrue(session().sentAsdus().isEmpty());
  }

  @Test
  void negativeOrUnknownTestConfirmationFails() {
    client.connect();
    CompletionStage<TestResult> negative = client.testAsync(STATION);
    deliver(reply(lastSent(), Cause.ACTIVATION_CONFIRMATION, true));
    failure(negative, NegativeConfirmationException.class);

    CompletionStage<TestResult> unknown = client.testAsync(STATION);
    deliver(reply(lastSent(), Cause.UNKNOWN_TYPE_ID, false));
    assertEquals(
        Cause.UNKNOWN_TYPE_ID, failure(unknown, NegativeConfirmationException.class).cause());
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void oneTestPerStation() {
    client.connect();
    client.testAsync(STATION);
    failure(client.testAsync(STATION, AsduType.C_TS_NA_1), RequestInProgressException.class);
    client.testAsync(CommonAddress.of(2));
    assertEquals(2, client.pendingRequestCount());
    assertEquals(2, session().sentAsdus().size());
  }

  @Test
  void testUsesCommandTimeout() {
    client.close();
    client =
        newClient(
            ClientConfig.builder()
                .commandTimeout(Duration.ofMillis(100))
                .requestTimeout(Duration.ofMillis(1000)));
    client.connect();
    CompletionStage<TestResult> stage = client.testAsync(STATION);

    clock.advance(100, TimeUnit.MILLISECONDS);

    failure(stage, ProtocolTimeoutException.class);
    assertEquals(0, client.pendingRequestCount());
  }

  // --- Reset process ----------------------------------------------------------------------------

  @Test
  void resetProcessCompletesOnTheEchoedConfirmation() {
    client.connect();
    CompletionStage<Void> stage =
        client.resetProcessAsync(STATION, QualifierOfResetProcess.GENERAL);
    Asdu request = lastSent();
    assertEquals(AsduType.C_RP_NA_1, request.type());
    assertEquals(
        List.of(new ResetProcessCommand(IOA_0, QualifierOfResetProcess.GENERAL)),
        request.objects());

    deliver(
        withObjects(
            reply(request, Cause.ACTIVATION_CONFIRMATION, false),
            List.of(new ResetProcessCommand(IOA_0, QualifierOfResetProcess.EVENT_BUFFER))));
    assertFalse(stage.toCompletableFuture().isDone(), "the QRP must be echoed");

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    join(stage);
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void negativeResetFails() {
    client.connect();
    CompletionStage<Void> stage =
        client.resetProcessAsync(STATION, QualifierOfResetProcess.GENERAL);
    deliver(reply(lastSent(), Cause.ACTIVATION_CONFIRMATION, true));
    failure(stage, NegativeConfirmationException.class);
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void resetFailsWhenTheConnectionDropsBeforeConfirmation() {
    client.connect();
    CompletionStage<Void> stage =
        client.resetProcessAsync(STATION, QualifierOfResetProcess.GENERAL);
    CompletionStage<CounterInterrogationResult> counter = client.counterInterrogateAsync(STATION);

    session().fireConnectionLost(null);

    failure(stage, ConnectionClosedException.class);
    failure(counter, ConnectionClosedException.class);
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void oneResetPerStation() {
    client.connect();
    client.resetProcessAsync(STATION, QualifierOfResetProcess.GENERAL);
    failure(
        client.resetProcessAsync(STATION, QualifierOfResetProcess.EVENT_BUFFER),
        RequestInProgressException.class);
    assertEquals(1, session().sentAsdus().size());
  }

  @Test
  void sendFailureFailsTheRequestWithoutLeaking() {
    client.connect();
    // A closed fake session fails every send without reporting a connection loss.
    session().close();

    failure(
        client.resetProcessAsync(STATION, QualifierOfResetProcess.GENERAL),
        ConnectionClosedException.class);
    failure(client.counterInterrogateAsync(STATION), ConnectionClosedException.class);
    failure(client.testAsync(STATION), ConnectionClosedException.class);
    assertEquals(0, client.pendingRequestCount());
  }

  // --- Parameters -------------------------------------------------------------------------------

  @Test
  void positiveParameterLoadReturnsTheValueInOperation() {
    client.connect();
    CompletionStage<ParameterResult> stage =
        client.parameters().loadAsync(new ParameterRequest.ShortFloat(PARAMETER, 80.0f, qpm()));
    Asdu request = lastSent();
    assertEquals(AsduType.P_ME_NC_1, request.type());
    assertEquals(
        List.of(new ParameterShortFloat(PARAMETER.objectAddress(), 80.0f, qpm())),
        request.objects());

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));

    ParameterResult result = join(stage);
    assertTrue(result.positive());
    assertEquals(PARAMETER, result.target());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, result.cause());
    assertEquals(Optional.of(request.objects().get(0)), result.valueInOperation());
  }

  @Test
  void rejectedParameterLoadReturnsTheOldValue() {
    client.connect();
    CompletionStage<ParameterResult> stage =
        client.parameters().loadAsync(new ParameterRequest.Scaled(PARAMETER, (short) 50, qpm()));
    Asdu request = lastSent();
    var old = new ParameterScaled(PARAMETER.objectAddress(), (short) 10, qpm());

    deliver(withObjects(reply(request, Cause.ACTIVATION_CONFIRMATION, true), List.of(old)));

    ParameterResult result = join(stage);
    assertFalse(result.positive());
    assertEquals(Optional.of(old), result.valueInOperation());
  }

  @Test
  void unknownAddressParameterLoadReturnsNoValue() {
    client.connect();
    CompletionStage<ParameterResult> stage =
        client
            .parameters()
            .loadAsync(new ParameterRequest.Normalized(PARAMETER, NormalizedValue.of(0.5), qpm()));
    assertEquals(AsduType.P_ME_NA_1, lastSent().type());

    deliver(reply(lastSent(), Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, false));

    ParameterResult result = join(stage);
    assertFalse(result.positive());
    assertEquals(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, result.cause());
    assertEquals(Optional.empty(), result.valueInOperation());
  }

  @Test
  void parameterLoadIgnoresInterrogationResponsesAndOtherAddresses() {
    client.connect();
    CompletionStage<ParameterResult> stage =
        client.parameters().loadAsync(new ParameterRequest.Scaled(PARAMETER, (short) 50, qpm()));
    Asdu request = lastSent();
    var other = new ParameterScaled(InformationObjectAddress.of(4002), (short) 50, qpm());

    deliver(reply(request, Cause.INTERROGATED_BY_STATION, false));
    deliver(reply(request, Cause.INTERROGATED_BY_GROUP_16, false));
    deliver(withObjects(reply(request, Cause.ACTIVATION_CONFIRMATION, false), List.of(other)));
    assertFalse(stage.toCompletableFuture().isDone());
    assertEquals(1, client.pendingRequestCount());

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    assertTrue(join(stage).positive());
  }

  @Test
  void oneParameterLoadPerAddressAcrossTypes() {
    client.connect();
    client.parameters().loadAsync(new ParameterRequest.Scaled(PARAMETER, (short) 1, qpm()));
    failure(
        client.parameters().loadAsync(new ParameterRequest.ShortFloat(PARAMETER, 1.0f, qpm())),
        RequestInProgressException.class);

    client
        .parameters()
        .loadAsync(new ParameterRequest.Scaled(PointAddress.of(1, 4002), (short) 1, qpm()));
    // Activation of the same address is a different kind of request.
    client.parameters().activateAsync(PARAMETER, QualifierOfParameterActivation.of(3));
    assertEquals(3, client.pendingRequestCount());
    assertEquals(3, session().sentAsdus().size());
  }

  @Test
  void parameterActivationCompletesOnConfirmation() {
    client.connect();
    var qpa = QualifierOfParameterActivation.of(3);
    CompletionStage<Void> stage = client.parameters().activateAsync(PARAMETER, qpa);
    Asdu request = lastSent();
    assertEquals(AsduType.P_AC_NA_1, request.type());
    assertEquals(Cause.ACTIVATION, request.cause());
    assertEquals(
        List.of(new ParameterActivation(PARAMETER.objectAddress(), qpa)), request.objects());

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    join(stage);

    CompletionStage<Void> rejected = client.parameters().activateAsync(PARAMETER, qpa);
    deliver(reply(lastSent(), Cause.ACTIVATION_CONFIRMATION, true));
    failure(rejected, NegativeConfirmationException.class);
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void parameterDeactivationExpectsDeactivationConfirmation() {
    client.connect();
    var qpa = QualifierOfParameterActivation.of(3);
    CompletionStage<Void> stage = client.parameters().deactivateAsync(PARAMETER, qpa);
    Asdu request = lastSent();
    assertEquals(Cause.DEACTIVATION, request.cause());

    deliver(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
    assertFalse(stage.toCompletableFuture().isDone());

    deliver(reply(request, Cause.DEACTIVATION_CONFIRMATION, false));
    join(stage);
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void parameterActivationFailsOnUnknownCause() {
    client.connect();
    CompletionStage<Void> stage =
        client.parameters().activateAsync(PARAMETER, QualifierOfParameterActivation.of(3));
    deliver(reply(lastSent(), Cause.UNKNOWN_CAUSE, false));
    failure(stage, NegativeConfirmationException.class);
  }

  // --- Broadcast guard
  // ----------------------------------------------------------------------------

  @Test
  void globalAddressIsRejectedBeforeSending() {
    client.connect();
    CommonAddress global = CommonAddress.of(65535);
    PointAddress globalPoint = new PointAddress(global, PARAMETER.objectAddress());

    List<Runnable> calls =
        List.of(
            () -> client.interrogateAsync(global),
            () -> client.synchronizeClockAsync(global, Instant.now()),
            () -> client.counterInterrogateAsync(global),
            () -> client.testAsync(global),
            () -> client.resetProcessAsync(global, QualifierOfResetProcess.GENERAL),
            () ->
                client
                    .parameters()
                    .loadAsync(new ParameterRequest.Scaled(globalPoint, (short) 1, qpm())),
            () ->
                client
                    .parameters()
                    .activateAsync(globalPoint, QualifierOfParameterActivation.of(3)),
            () ->
                client
                    .parameters()
                    .deactivateAsync(globalPoint, QualifierOfParameterActivation.of(3)));
    for (Runnable call : calls) {
      assertThrows(IllegalArgumentException.class, call::run);
    }
    assertThrows(IllegalArgumentException.class, () -> client.interrogate(global));
    assertTrue(session().sentAsdus().isEmpty());
    assertEquals(0, client.pendingRequestCount());
  }

  @Test
  void globalAddressFollowsTheCommonAddressLength() {
    client.close();
    client = newClient(ClientConfig.builder().protocolProfile(new ProtocolProfile(2, 1, 2, 255)));
    client.connect();

    assertThrows(
        IllegalArgumentException.class,
        () -> client.counterInterrogateAsync(CommonAddress.of(255)));
    assertTrue(session().sentAsdus().isEmpty());

    client.close();
    client = newClient(ClientConfig.builder());
    client.connect();
    client.counterInterrogateAsync(CommonAddress.of(255));
    assertEquals(1, session().sentAsdus().size(), "255 is an ordinary station with a 2-octet CA");
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private DefaultIec60870Client newClient(ClientConfig.Builder builder) {
    ClientConfig config = builder.callbackExecutor(Runnable::run).originatorAddress(OWN_OA).build();
    DefaultIec60870Client created =
        new DefaultIec60870Client(
            new FakeClientTransport(),
            config,
            (sessionEvents, scheduler) -> {
              FakeSession session = FakeSession.client(sessionEvents);
              sessionRef.set(session);
              return session;
            },
            clock);
    created
        .events()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ClientEvent item) {
                events.add(item);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    return created;
  }

  private FakeSession session() {
    FakeSession session = sessionRef.get();
    if (session == null) {
      throw new IllegalStateException("session not yet built");
    }
    return session;
  }

  private Asdu lastSent() {
    List<Asdu> sent = session().sentAsdus();
    return sent.get(sent.size() - 1);
  }

  private void deliver(Asdu asdu) {
    session().deliverAsdu(asdu);
  }

  private static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }

  private static <E extends Throwable> E failure(CompletionStage<?> stage, Class<E> type) {
    CompletionException e =
        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    return assertInstanceOf(type, e.getCause());
  }

  /** A reply from the station echoing the request's objects, with OA 0. */
  private static Asdu reply(Asdu request, Cause cause, boolean negative) {
    return new Asdu(
        request.type(),
        false,
        cause,
        negative,
        false,
        OriginatorAddress.none(),
        request.commonAddress(),
        request.objects());
  }

  private static Asdu withObjects(Asdu a, List<InformationObject> objects) {
    return new Asdu(
        a.type(),
        a.sequence(),
        a.cause(),
        a.negative(),
        a.test(),
        a.originatorAddress(),
        a.commonAddress(),
        objects);
  }

  private static Asdu withOriginator(Asdu a, int originator) {
    return new Asdu(
        a.type(),
        a.sequence(),
        a.cause(),
        a.negative(),
        a.test(),
        OriginatorAddress.of(originator),
        a.commonAddress(),
        a.objects());
  }

  private static Asdu withTest(Asdu a) {
    return new Asdu(
        a.type(),
        a.sequence(),
        a.cause(),
        a.negative(),
        true,
        a.originatorAddress(),
        a.commonAddress(),
        a.objects());
  }

  private static Asdu withStation(Asdu a, int station) {
    return new Asdu(
        a.type(),
        a.sequence(),
        a.cause(),
        a.negative(),
        a.test(),
        a.originatorAddress(),
        CommonAddress.of(station),
        a.objects());
  }

  private static Asdu counters(Cause cause, int... addresses) {
    List<InformationObject> objects = new ArrayList<>();
    for (int address : addresses) {
      objects.add(
          new IntegratedTotals(
              InformationObjectAddress.of(address),
              new BinaryCounterReading(address, 0, false, false, false)));
    }
    return new Asdu(
        AsduType.M_IT_NA_1, false, cause, false, false, OriginatorAddress.none(), STATION, objects);
  }

  private static Asdu monitor(Cause cause, int address) {
    return new Asdu(
        AsduType.M_ME_NB_1,
        false,
        cause,
        false,
        false,
        OriginatorAddress.none(),
        STATION,
        List.of(
            new MeasuredValueScaled(
                InformationObjectAddress.of(address),
                (short) 1,
                new Qds(false, false, false, false, false))));
  }

  private static List<Long> addresses(List<InformationObject> objects) {
    return objects.stream().map(o -> o.address().value().longValue()).toList();
  }

  private static QualifierOfCounterInterrogation qcc(int request, FreezeMode freeze) {
    return new QualifierOfCounterInterrogation(request, freeze);
  }

  private static QualifierOfParameter qpm() {
    return new QualifierOfParameter(1, false, false);
  }
}
