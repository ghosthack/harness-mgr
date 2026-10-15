package io.github.ghosthack.harnessmanager.host;

/**
 * Per-attach choices the host bakes into the connection. {@code trueColor}: whether
 * the client advertises 24-bit colour to the substrate (tmux quantizes to the 256
 * palette for clients that don't) — read from settings at attach time, so it reaches
 * connections opened from then on, exactly as before the seam existed.
 */
public record AttachOptions(boolean trueColor) {
}
