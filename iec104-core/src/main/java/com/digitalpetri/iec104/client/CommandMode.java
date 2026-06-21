package com.digitalpetri.iec104.client;

/**
 * The procedure used to deliver a control command.
 *
 * <p>{@link #directExecute()} sends a single activation with the select/execute flag clear (S/E =
 * 0); the controlled station acts on it immediately. {@link #selectBeforeOperate()} performs the
 * two-step procedure: a select activation (S/E = 1) that the station confirms, followed by an
 * execute activation (S/E = 0). Use select-before-operate for safety-critical points where the
 * station is expected to reserve the output before it is operated.
 *
 * <pre>{@code
 * CommandResult r = client.commands().send(command, CommandMode.selectBeforeOperate());
 * }</pre>
 */
public enum CommandMode {

  /** Direct execute: a single activation with the select/execute flag clear. */
  DIRECT_EXECUTE,

  /** Select before operate: a select activation followed by an execute activation. */
  SELECT_BEFORE_OPERATE;

  /**
   * Returns the direct-execute mode.
   *
   * @return {@link #DIRECT_EXECUTE}.
   */
  public static CommandMode directExecute() {
    return DIRECT_EXECUTE;
  }

  /**
   * Returns the select-before-operate mode.
   *
   * @return {@link #SELECT_BEFORE_OPERATE}.
   */
  public static CommandMode selectBeforeOperate() {
    return SELECT_BEFORE_OPERATE;
  }
}
