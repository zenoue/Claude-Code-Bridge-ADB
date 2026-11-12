package com.zenoue.ccbridge.feature

/**
 * Text behind the "Sao chép cho Claude Code" button.
 *
 * It is self-contained: pasted into Claude Code (or a plain shell) it downloads the client script and
 * explains how to use it, so the user does not have to clone this repository. Every line is either a
 * shell command or a `#` comment, hence the block is also valid when pasted straight into a terminal.
 * It is read by Claude Code, therefore English.
 */
object ClaudeSetup {
    /** Branch that carries tools/bridge.py (the branch CI builds from). */
    const val SCRIPT_BRANCH = "main"
    const val SCRIPT_PATH = "~/.ccbridge/bridge.py"

    fun scriptUrl(repo: String) = "https://raw.githubusercontent.com/$repo/$SCRIPT_BRANCH/tools/bridge.py"

    /** [tunnelUrl] is only a fallback for the fixed address; null while the tunnel is down. */
    fun block(repo: String, bridgeId: String, password: String, tunnelUrl: String?): String {
        val run = "python3 $SCRIPT_PATH"
        return buildString {
            append("# Android phone bridge (https://github.com/$repo): run `adb shell` commands on my phone.\n")
            append("# No clone needed - download the client once (Python 3, standard library only):\n")
            append("mkdir -p ~/.ccbridge && curl -fsSL ${scriptUrl(repo)} -o $SCRIPT_PATH\n")
            append("export BRIDGE_ID='$bridgeId'\n")
            append("export BRIDGE_PASSWORD='$password'\n")
            if (tunnelUrl != null) append("export BRIDGE_URL='$tunnelUrl'\n")
            append("$run ping   # always ping first\n")
            append("#\n")
            append("# Usage (BRIDGE_ID and BRIDGE_PASSWORD must be set in every shell that runs it):\n")
            append("#   $run exec \"dumpsys battery\"      # exit code = exit code on the phone\n")
            append("#   $run logcat \"logcat -v time\"     # for commands that never end; stop: stop-logcat\n")
            append("#   $run console                     # the app's own log (tunnel / ADB events)\n")
            append("#   $run --help                      # everything else\n")
            append("# Notes:\n")
            append("#   - Errors from the app exit 1: -2 wrong password, -3 blocked (read-only mode is on in the app),\n")
            append("#     -4 ADB not connected, -5 clock skew > 5 min, -6 duplicate request. 124 = timed out\n")
            append("#     (BRIDGE_TIMEOUT seconds, default 90, max 600).\n")
            append("#   - Keep output small (| head, | tail, | grep): a very large reply can be cut off by the tunnel.\n")
            append("#     For big dumps write to /data/local/tmp/x.txt on the phone first, then read parts of it.\n")
            append("#   - \"no reachable tunnel\": the bridge is off in the app, or this network blocks ntfy.sh / *.lhr.life.\n")
            append("#     The tunnel URL changes by itself; the client finds the new one through BRIDGE_ID. Just retry.\n")
            append("#   - Every command is shown to the phone's owner in the app (CLI tab).")
        }
    }
}
