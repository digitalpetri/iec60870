package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.session.Session;

/**
 * The internal FT1.2 mode-strategy SPI: one engine per link transmission procedure.
 *
 * <p>An {@code Ft12Engine} is the concrete link state machine behind the public {@link
 * Ft12LinkLayer} dispatcher. {@link Ft12LinkLayer} selects an engine by {@code (role, mode)} at
 * construction and forwards every {@link Session} call plus {@link #onFrame(Ft12Frame)} to it, so
 * each transmission procedure — the balanced point-to-point machine and the unbalanced
 * master/secondary machines — is realized as a self-contained engine that implements the neutral
 * {@link Session} contract and additionally accepts inbound FT1.2 frames.
 *
 * <p>This type is an implementation detail of the cs101 module and is not part of the published
 * API; consumers see only {@link Ft12LinkLayer}.
 */
interface Ft12Engine extends Session {

  /**
   * Handles a single inbound FT1.2 frame.
   *
   * <p>The same contract as {@link Ft12LinkLayer#onFrame(Ft12Frame)}: the engine matches secondary
   * (response) and single-character frames against its outstanding primary transaction and routes
   * primary (request) frames through its secondary process.
   *
   * @param frame the inbound frame.
   */
  void onFrame(Ft12Frame frame);
}
