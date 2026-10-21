package com.digitalpetri.iec60870.client;

/**
 * When a command's result stage completes.
 *
 * <p>An outstation confirms a command with an activation confirmation (ACT_CON, COT 7) once it has
 * accepted it, and may later send an activation termination (ACT_TERM, COT 10) once the command
 * procedure has finished, for example when a pulse output ends. Neither proves that the equipment
 * reached the new state; return information on the monitor point does that.
 *
 * <p>Many outstations never send ACT_TERM, so {@link #CONFIRMATION} is the default. Choose {@link
 * #TERMINATION} for outstations that declare ACT_TERM in their interoperability list.
 *
 * @see ClientConfig.Builder#commandCompletion(CommandCompletion)
 * @see ClientConfig.Builder#setpointCompletion(CommandCompletion)
 * @see CommandOptions#withCompletion(CommandCompletion)
 */
public enum CommandCompletion {

  /** Complete on the ACT_CON of the final phase. */
  CONFIRMATION,

  /**
   * Complete on the ACT_TERM that follows a positive ACT_CON of the final phase, or with {@link
   * CommandOutcome#TERMINATION_MISSING} when none arrives before the termination timeout.
   */
  TERMINATION
}
