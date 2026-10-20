package com.digitalpetri.iec60870.client;

/**
 * How the client compares the CP56Time2a time tag of a command's echo with the tag it sent.
 *
 * <p>A controlled station confirms a command by echoing it. For time-tagged commands (C_SC_TA_1
 * through C_BO_TA_1) the echo carries the time tag too. Matching on the time tag tells a new
 * command from a late echo of an earlier, otherwise identical one. Some stations echo the tag with
 * a different day of week or different flag bits, which carry no identity. The type, address,
 * value, qualifier, and select/execute flag must match in every mode.
 *
 * @see ClientConfig.Builder#commandTimeTagMatch(TimeTagMatch)
 */
public enum TimeTagMatch {

  /**
   * Every field of the echoed time tag must equal the sent one, including flags and day of week.
   */
  EXACT,

  /**
   * The milliseconds, minute, hour, day of month, month, and year must match. Day of week, IV, SU,
   * and RES1/GEN are ignored. This is the default.
   */
  INSTANT,

  /**
   * The time tag is not compared. A late echo of an earlier command with the same value and
   * qualifier can then confirm a new one, as it can for untimed commands.
   */
  IGNORE
}
