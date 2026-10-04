#!/bin/bash
# Start/stop the AgentCraft Foreman for a game launched from Prism (or anything without a terminal).
#
# Prism and other GUI apps inherit launchd's bare PATH: no mise node, no dotnet@8/DOTNET_ROOT, no
# /opt/homebrew/bin (az, gh, cargo), no ~/.local/bin (claude, credential helpers). So every command
# runs tools/foreman-daemon.mjs through a login interactive zsh (`/bin/zsh -lic`), which reads
# ~/.zprofile and ~/.zshrc like a Terminal tab does.
#
#   foreman-daemon.sh start [opts]      returns at once with exit 0 (the work runs in the background),
#                                       so a Prism PreLaunchCommand never blocks or fails the launch.
#                                       Prism aborts the launch on a non-zero PreLaunch exit code.
#   foreman-daemon.sh start --wait      runs in the foreground (for a terminal)
#   foreman-daemon.sh stop|restart|status [opts]
#   foreman-daemon.sh after-exit [opts] -- CMD...
#                                       Prism PostExitCommand: runs CMD (the world backup) first and
#                                       exits with its code, then stops the Foreman.
#
# Options: --profile NAME (default hardcore) --port N (default 7880) --home PATH (default ~/.agentcraft)
# Log: <checkout>/artifacts/logs/foreman-daemon-<profile>.log
#
# bash 3.2 (macOS /bin/bash): no bash 4 features. No `set -e`: `start` must reach `exit 0`.

here="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
root="$(dirname "$here")"
action="${1:-start}"
[ $# -gt 0 ] && shift

profile="hardcore"
prev=""
for a in "$@"; do
  [ "$a" = "--" ] && break
  [ "$prev" = "--profile" ] && profile="$a"
  prev="$a"
done
case "$profile" in *[!A-Za-z0-9_-]*|"") profile="hardcore" ;; esac

log_dir="$root/artifacts/logs"
log="$log_dir/foreman-daemon-$profile.log"
mkdir -p "$log_dir" 2>/dev/null

# Inside the login shell: fall back to mise's shims / Homebrew when the rc files did not put node on
# PATH (e.g. mise activated only from a prompt hook), then run the node entry. Single quotes on
# purpose: the login shell expands these.
# shellcheck disable=SC2016
inner='command -v node >/dev/null 2>&1 || export PATH="$HOME/.local/share/mise/shims:/opt/homebrew/bin:/usr/local/bin:$PATH"; exec node "$0" "$@"'

run_node() {
  /bin/zsh -lic "$inner" "$here/foreman-daemon.mjs" "$@" </dev/null
}

stamp() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }

case "$action" in
  start)
    wait_flag=0
    for a in "$@"; do [ "$a" = "--wait" ] && wait_flag=1; done
    if [ "$wait_flag" = 1 ]; then
      run_node start "$@"
      exit $?
    fi
    {
      echo "[foreman-daemon.sh $(stamp)] start $* (background)"
    } >>"$log" 2>/dev/null
    # Detach completely from the caller's pipes (Prism waits for the command and reads its output).
    ( run_node start "$@" >>"$log" 2>&1 </dev/null & ) >/dev/null 2>&1 </dev/null
    exit 0
    ;;
  after-exit)
    opts=()
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do opts+=("$1"); shift; done
    [ "$1" = "--" ] && shift
    rc=0
    if [ $# -gt 0 ]; then
      "$@"
      rc=$?
    fi
    echo "[foreman-daemon.sh $(stamp)] game exited; stopping the Foreman (post-exit command exit code $rc)" >>"$log" 2>/dev/null
    run_node stop "${opts[@]}" >>"$log" 2>&1
    exit $rc
    ;;
  stop|restart|status)
    run_node "$action" "$@"
    exit $?
    ;;
  -h|--help|help)
    run_node --help
    exit 0
    ;;
  *)
    echo "usage: foreman-daemon.sh start|stop|restart|status|after-exit [options]" >&2
    exit 2
    ;;
esac
