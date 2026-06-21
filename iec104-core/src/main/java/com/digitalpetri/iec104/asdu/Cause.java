package com.digitalpetri.iec104.asdu;

import com.digitalpetri.iec104.AsduDecodeException;

/**
 * Cause of transmission (the six-bit cause field of octet 3 of the ASDU) as defined by IEC
 * 60870-5-101 Table 14.
 *
 * <p>Only the standard (compatible) range {@code 1..47} is modelled. The private range {@code
 * 48..63}, the reserved gaps, and the unused value {@code 0} have no constant; {@link
 * #fromValue(int)} rejects them with {@link AsduDecodeException}.
 *
 * <p>The cause field does not carry the test or positive/negative bits; those occupy the two most
 * significant bits of octet 3 and are modelled separately on {@link Asdu}.
 */
public enum Cause {

  /** Periodic, cyclic (per/cyc). */
  PERIODIC(1),
  /** Background scan (back). */
  BACKGROUND_SCAN(2),
  /** Spontaneous (spont). */
  SPONTANEOUS(3),
  /** Initialized (init). */
  INITIALIZED(4),
  /** Request or requested (req). */
  REQUEST(5),
  /** Activation (act). */
  ACTIVATION(6),
  /** Activation confirmation (actcon). */
  ACTIVATION_CONFIRMATION(7),
  /** Deactivation (deact). */
  DEACTIVATION(8),
  /** Deactivation confirmation (deactcon). */
  DEACTIVATION_CONFIRMATION(9),
  /** Activation termination (actterm). */
  ACTIVATION_TERMINATION(10),
  /** Return information caused by a remote command (retrem). */
  RETURN_REMOTE(11),
  /** Return information caused by a local command (retloc). */
  RETURN_LOCAL(12),
  /** File transfer (file). */
  FILE_TRANSFER(13),
  /** Interrogated by station interrogation (inrogen). */
  INTERROGATED_BY_STATION(20),
  /** Interrogated by group 1 interrogation (inro1). */
  INTERROGATED_BY_GROUP_1(21),
  /** Interrogated by group 2 interrogation (inro2). */
  INTERROGATED_BY_GROUP_2(22),
  /** Interrogated by group 3 interrogation (inro3). */
  INTERROGATED_BY_GROUP_3(23),
  /** Interrogated by group 4 interrogation (inro4). */
  INTERROGATED_BY_GROUP_4(24),
  /** Interrogated by group 5 interrogation (inro5). */
  INTERROGATED_BY_GROUP_5(25),
  /** Interrogated by group 6 interrogation (inro6). */
  INTERROGATED_BY_GROUP_6(26),
  /** Interrogated by group 7 interrogation (inro7). */
  INTERROGATED_BY_GROUP_7(27),
  /** Interrogated by group 8 interrogation (inro8). */
  INTERROGATED_BY_GROUP_8(28),
  /** Interrogated by group 9 interrogation (inro9). */
  INTERROGATED_BY_GROUP_9(29),
  /** Interrogated by group 10 interrogation (inro10). */
  INTERROGATED_BY_GROUP_10(30),
  /** Interrogated by group 11 interrogation (inro11). */
  INTERROGATED_BY_GROUP_11(31),
  /** Interrogated by group 12 interrogation (inro12). */
  INTERROGATED_BY_GROUP_12(32),
  /** Interrogated by group 13 interrogation (inro13). */
  INTERROGATED_BY_GROUP_13(33),
  /** Interrogated by group 14 interrogation (inro14). */
  INTERROGATED_BY_GROUP_14(34),
  /** Interrogated by group 15 interrogation (inro15). */
  INTERROGATED_BY_GROUP_15(35),
  /** Interrogated by group 16 interrogation (inro16). */
  INTERROGATED_BY_GROUP_16(36),
  /** Requested by general counter request (reqcogen). */
  REQUESTED_BY_GENERAL_COUNTER(37),
  /** Requested by group 1 counter request (reqco1). */
  REQUESTED_BY_GROUP_1_COUNTER(38),
  /** Requested by group 2 counter request (reqco2). */
  REQUESTED_BY_GROUP_2_COUNTER(39),
  /** Requested by group 3 counter request (reqco3). */
  REQUESTED_BY_GROUP_3_COUNTER(40),
  /** Requested by group 4 counter request (reqco4). */
  REQUESTED_BY_GROUP_4_COUNTER(41),
  /** Unknown type identification. */
  UNKNOWN_TYPE_ID(44),
  /** Unknown cause of transmission. */
  UNKNOWN_CAUSE(45),
  /** Unknown common address of ASDU. */
  UNKNOWN_COMMON_ADDRESS(46),
  /** Unknown information object address. */
  UNKNOWN_INFORMATION_OBJECT_ADDRESS(47);

  private final int value;

  Cause(int value) {
    this.value = value;
  }

  /**
   * Returns the numeric cause value carried in the six low bits of octet 3 of the ASDU.
   *
   * @return the cause value (1..47).
   */
  public int value() {
    return value;
  }

  /**
   * Returns the {@link Cause} for the given numeric cause value.
   *
   * @param value the cause value read from the six low bits of octet 3 of an ASDU.
   * @return the matching {@link Cause}.
   * @throws AsduDecodeException if {@code value} is the unused value {@code 0}, a reserved code, or
   *     a private-range code {@code 48..63} that has no defined constant.
   */
  public static Cause fromValue(int value) {
    for (Cause cause : values()) {
      if (cause.value == value) {
        return cause;
      }
    }
    throw new AsduDecodeException("undefined cause of transmission: " + value);
  }
}
