package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.Cause;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The application's hook into a server's request handling.
 *
 * <p>Every method has a default implementing the standard outstation behavior, so an application
 * implements only the operations it wants to customize. Each operation comes in a blocking form and
 * an asynchronous {@code *Async} form; the server invokes the asynchronous form, whose default
 * simply completes with the result of the blocking form. Override the blocking form for synchronous
 * logic, or the asynchronous form when the answer depends on an I/O-bound or otherwise non-blocking
 * computation.
 *
 * <p>Callbacks for a single connection are serialized: a handler never observes two callbacks for
 * the same connection concurrently. Callbacks are invoked on the server's callback executor, off
 * any transport I/O thread, so a blocking implementation is permitted.
 *
 * <p>Default behavior:
 *
 * <ul>
 *   <li>{@link #onInterrogation} answers from the matched station's value image.
 *   <li>{@link #onRead} answers the addressed point's current value from the station image.
 *   <li>{@link #onCommand} rejects with {@link Cause#UNKNOWN_INFORMATION_OBJECT_ADDRESS}.
 *   <li>{@link #onClockSync} accepts.
 *   <li>{@link #onReset} accepts.
 *   <li>{@link #onRawAsdu} returns {@code false} (the ASDU is handled by the standard dispatch).
 * </ul>
 *
 * <pre>{@code
 * ServerHandler handler = new ServerHandler() {
 *   @Override public CommandDecision onCommand(ServerContext ctx, CommandRequest request) {
 *     boolean on = request.commandObject() instanceof SingleCommand sc && sc.on();
 *     return CommandDecision.acceptAndUpdate(PointValue.single(on));
 *   }
 * };
 * }</pre>
 */
public interface ServerHandler {

  /**
   * Answers an interrogation request.
   *
   * <p>The default returns {@link ServerContext#defaultInterrogation(InterrogationRequest)}, which
   * reports every reported point of the matched station selected by the request's qualifier.
   *
   * @param context the per-request context.
   * @param request the interrogation request.
   * @return the interrogation response.
   */
  default InterrogationResponse onInterrogation(
      ServerContext context, InterrogationRequest request) {
    return context.defaultInterrogation(request);
  }

  /**
   * Answers an interrogation request asynchronously.
   *
   * <p>The default completes with the result of {@link #onInterrogation(ServerContext,
   * InterrogationRequest)}.
   *
   * @param context the per-request context.
   * @param request the interrogation request.
   * @return a stage that completes with the interrogation response.
   */
  default CompletionStage<InterrogationResponse> onInterrogationAsync(
      ServerContext context, InterrogationRequest request) {
    return CompletableFuture.completedFuture(onInterrogation(context, request));
  }

  /**
   * Answers a read request.
   *
   * <p>The default returns {@link ServerContext#defaultRead(ReadRequest)}, which reports the
   * addressed point's current value from the matched station's image.
   *
   * @param context the per-request context.
   * @param request the read request.
   * @return the read response.
   */
  default ReadResponse onRead(ServerContext context, ReadRequest request) {
    return context.defaultRead(request);
  }

  /**
   * Answers a read request asynchronously.
   *
   * <p>The default completes with the result of {@link #onRead(ServerContext, ReadRequest)}.
   *
   * @param context the per-request context.
   * @param request the read request.
   * @return a stage that completes with the read response.
   */
  default CompletionStage<ReadResponse> onReadAsync(ServerContext context, ReadRequest request) {
    return CompletableFuture.completedFuture(onRead(context, request));
  }

  /**
   * Decides a control command.
   *
   * <p>The default rejects every command with {@link Cause#UNKNOWN_INFORMATION_OBJECT_ADDRESS}; an
   * application that hosts commandable points must override this to accept and, typically, update
   * the commanded point's value with {@link CommandDecision#acceptAndUpdate}.
   *
   * @param context the per-request context.
   * @param request the command request.
   * @return the command decision.
   */
  default CommandDecision onCommand(ServerContext context, CommandRequest request) {
    return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
  }

  /**
   * Decides a control command asynchronously.
   *
   * <p>The default completes with the result of {@link #onCommand(ServerContext, CommandRequest)}.
   *
   * @param context the per-request context.
   * @param request the command request.
   * @return a stage that completes with the command decision.
   */
  default CompletionStage<CommandDecision> onCommandAsync(
      ServerContext context, CommandRequest request) {
    return CompletableFuture.completedFuture(onCommand(context, request));
  }

  /**
   * Decides a clock synchronization request.
   *
   * <p>The default accepts. Override to apply the supplied time to a real clock or to reject the
   * request.
   *
   * @param context the per-request context.
   * @param request the clock synchronization request.
   * @return the clock synchronization decision.
   */
  default ClockSyncDecision onClockSync(ServerContext context, ClockSyncRequest request) {
    return ClockSyncDecision.accept();
  }

  /**
   * Decides a clock synchronization request asynchronously.
   *
   * <p>The default completes with the result of {@link #onClockSync(ServerContext,
   * ClockSyncRequest)}.
   *
   * @param context the per-request context.
   * @param request the clock synchronization request.
   * @return a stage that completes with the clock synchronization decision.
   */
  default CompletionStage<ClockSyncDecision> onClockSyncAsync(
      ServerContext context, ClockSyncRequest request) {
    return CompletableFuture.completedFuture(onClockSync(context, request));
  }

  /**
   * Decides a reset process request.
   *
   * <p>The default accepts. Override to perform the requested reset or to reject the request.
   *
   * @param context the per-request context.
   * @param request the reset request.
   * @return the reset decision.
   */
  // Params are unused by the accepting default but are part of the overridable hook contract.
  @SuppressWarnings("unused")
  default ResetDecision onReset(ServerContext context, ResetRequest request) {
    return ResetDecision.accept();
  }

  /**
   * Decides a reset process request asynchronously.
   *
   * <p>The default completes with the result of {@link #onReset(ServerContext, ResetRequest)}.
   *
   * @param context the per-request context.
   * @param request the reset request.
   * @return a stage that completes with the reset decision.
   */
  default CompletionStage<ResetDecision> onResetAsync(ServerContext context, ResetRequest request) {
    return CompletableFuture.completedFuture(onReset(context, request));
  }

  /**
   * Offers a received ASDU for application-level handling before the standard dispatch.
   *
   * <p>The default returns {@code false}, letting the server's standard dispatch process the ASDU.
   * Return {@code true} to claim the ASDU: the standard dispatch is then skipped and the handler is
   * responsible for any reply (for example via {@link ServerContext#send(Asdu)}). This is the hook
   * for type identifications the standard dispatch does not cover, such as private TypeIDs.
   *
   * @param context the per-request context.
   * @param asdu the received ASDU.
   * @return {@code true} if the handler consumed the ASDU, {@code false} to defer to standard
   *     dispatch.
   */
  // Params are unused by the deferring default but are part of the overridable hook contract.
  @SuppressWarnings("unused")
  default boolean onRawAsdu(ServerContext context, Asdu asdu) {
    return false;
  }

  /**
   * Offers a received ASDU for application-level handling asynchronously.
   *
   * <p>The default completes with the result of {@link #onRawAsdu(ServerContext, Asdu)}.
   *
   * @param context the per-request context.
   * @param asdu the received ASDU.
   * @return a stage that completes with {@code true} if the handler consumed the ASDU.
   */
  default CompletionStage<Boolean> onRawAsduAsync(ServerContext context, Asdu asdu) {
    return CompletableFuture.completedFuture(onRawAsdu(context, asdu));
  }
}
