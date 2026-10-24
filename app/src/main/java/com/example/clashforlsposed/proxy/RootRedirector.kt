package com.example.clashforlsposed.proxy

import java.util.UUID

object RootRedirector {
    private const val NAT_CHAIN = "CLASH_GOOGLE_NAT"
    private const val FILTER_CHAIN = "CLASH_GOOGLE_FILTER"
    private const val NAT_CHAIN_V6 = "CLASH_GOOGLE_NAT6"
    private const val FILTER_CHAIN_V6 = "CLASH_GOOGLE_FILTER6"
    private const val REDIR_PORT = 17892
    private const val DNS_PORT = 17953
    private const val WATCHDOG_FILE = "/data/local/tmp/clash_google_proxy_watchdog.token"
    private const val APP_PROCESS_MARKER = "com.example.clashforlsposed"

    fun apply(uids: List<Int>, ownerPid: Int): Result<Boolean> {
        if (uids.isEmpty()) return Result.failure(IllegalStateException("没有找到 Google 应用 UID"))
        if (!RootShell.isAvailable()) return Result.failure(IllegalStateException("未获得 root 权限"))
        require(ownerPid > 0)
        val watchdogToken = UUID.randomUUID().toString().replace("-", "")

        val script = buildString {
            appendLine("set -e")
            appendLine("iptables -t nat -N $NAT_CHAIN 2>/dev/null || true")
            appendLine("iptables -t nat -F $NAT_CHAIN")
            appendLine("iptables -t nat -C OUTPUT -j $NAT_CHAIN 2>/dev/null || iptables -t nat -I OUTPUT 1 -j $NAT_CHAIN")
            appendLine("iptables -t nat -A $NAT_CHAIN -d 127.0.0.0/8 -j RETURN")
            uids.forEach { uid ->
                appendLine("iptables -t nat -A $NAT_CHAIN -m owner --uid-owner $uid -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
                appendLine("iptables -t nat -A $NAT_CHAIN -m owner --uid-owner $uid -p tcp -j REDIRECT --to-ports $REDIR_PORT")
            }
            appendLine("iptables -t filter -N $FILTER_CHAIN 2>/dev/null || true")
            appendLine("iptables -t filter -F $FILTER_CHAIN")
            appendLine("iptables -t filter -C OUTPUT -j $FILTER_CHAIN 2>/dev/null || iptables -t filter -I OUTPUT 1 -j $FILTER_CHAIN")
            uids.forEach { uid ->
                appendLine("iptables -t filter -A $FILTER_CHAIN -m owner --uid-owner $uid -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable")
            }
            appendLine("IPV6_READY=0")
            appendLine("if ip6tables -t nat -S OUTPUT >/dev/null 2>&1; then")
            appendLine("  ip6tables -t nat -N $NAT_CHAIN_V6 2>/dev/null || true")
            appendLine("  ip6tables -t nat -F $NAT_CHAIN_V6")
            appendLine("  ip6tables -t nat -C OUTPUT -j $NAT_CHAIN_V6 2>/dev/null || ip6tables -t nat -I OUTPUT 1 -j $NAT_CHAIN_V6")
            appendLine("  ip6tables -t nat -A $NAT_CHAIN_V6 -d ::1/128 -j RETURN")
            uids.forEach { uid ->
                appendLine("  ip6tables -t nat -A $NAT_CHAIN_V6 -m owner --uid-owner $uid -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
                appendLine("  ip6tables -t nat -A $NAT_CHAIN_V6 -m owner --uid-owner $uid -p tcp -j REDIRECT --to-ports $REDIR_PORT")
            }
            appendLine("  ip6tables -t filter -N $FILTER_CHAIN_V6 2>/dev/null || true")
            appendLine("  ip6tables -t filter -F $FILTER_CHAIN_V6")
            appendLine("  ip6tables -t filter -C OUTPUT -j $FILTER_CHAIN_V6 2>/dev/null || ip6tables -t filter -I OUTPUT 1 -j $FILTER_CHAIN_V6")
            uids.forEach { uid ->
                appendLine("  ip6tables -t filter -A $FILTER_CHAIN_V6 -m owner --uid-owner $uid -p udp --dport 443 -j REJECT --reject-with icmp6-port-unreachable")
            }
            appendLine("  IPV6_READY=1")
            appendLine("else")
            appendLine("  echo IPV6_NAT_UNAVAILABLE")
            appendLine("fi")
            appendLine("echo IPV6_READY=\$IPV6_READY")
            appendLine(buildWatchdogStart(watchdogToken, ownerPid))
        }
        val result = RootShell.run(script)
        if (!result.succeeded) {
            clear()
            return Result.failure(IllegalStateException(result.output.ifBlank { "iptables 规则应用失败" }))
        }
        if (!result.output.contains("WATCHDOG_READY")) {
            clear()
            return Result.failure(IllegalStateException("root 规则已写入，但崩溃清理监视器启动失败"))
        }
        return Result.success(result.output.contains("IPV6_READY=1"))
    }

    fun clear(): ShellResult = RootShell.run(
        "if [ \"$(id -u 2>/dev/null)\" != \"0\" ]; then echo ROOT_REQUIRED; exit 1; fi; " +
            "status=0; " +
            "while iptables -t nat -C OUTPUT -j $NAT_CHAIN 2>/dev/null; do iptables -t nat -D OUTPUT -j $NAT_CHAIN || { status=1; break; }; done; " +
            "iptables -t nat -F $NAT_CHAIN 2>/dev/null; iptables -t nat -X $NAT_CHAIN 2>/dev/null; " +
            "iptables -t nat -C OUTPUT -j $NAT_CHAIN 2>/dev/null && status=1; iptables -t nat -S $NAT_CHAIN 2>/dev/null && status=1; " +
            "while iptables -t filter -C OUTPUT -j $FILTER_CHAIN 2>/dev/null; do iptables -t filter -D OUTPUT -j $FILTER_CHAIN || { status=1; break; }; done; " +
            "iptables -t filter -F $FILTER_CHAIN 2>/dev/null; iptables -t filter -X $FILTER_CHAIN 2>/dev/null; " +
            "iptables -t filter -C OUTPUT -j $FILTER_CHAIN 2>/dev/null && status=1; iptables -t filter -S $FILTER_CHAIN 2>/dev/null && status=1; " +
            "while ip6tables -t nat -C OUTPUT -j $NAT_CHAIN_V6 2>/dev/null; do ip6tables -t nat -D OUTPUT -j $NAT_CHAIN_V6 || { status=1; break; }; done; " +
            "ip6tables -t nat -F $NAT_CHAIN_V6 2>/dev/null; ip6tables -t nat -X $NAT_CHAIN_V6 2>/dev/null; " +
            "ip6tables -t nat -C OUTPUT -j $NAT_CHAIN_V6 2>/dev/null && status=1; ip6tables -t nat -S $NAT_CHAIN_V6 2>/dev/null && status=1; " +
            "while ip6tables -t filter -C OUTPUT -j $FILTER_CHAIN_V6 2>/dev/null; do ip6tables -t filter -D OUTPUT -j $FILTER_CHAIN_V6 || { status=1; break; }; done; " +
            "ip6tables -t filter -F $FILTER_CHAIN_V6 2>/dev/null; ip6tables -t filter -X $FILTER_CHAIN_V6 2>/dev/null; " +
            "ip6tables -t filter -C OUTPUT -j $FILTER_CHAIN_V6 2>/dev/null && status=1; ip6tables -t filter -S $FILTER_CHAIN_V6 2>/dev/null && status=1; " +
            "if [ \"${'$'}status\" -eq 0 ]; then rm -f $WATCHDOG_FILE || status=1; fi; " +
            "if [ \"${'$'}status\" -eq 0 ]; then echo RULES_CLEARED; else echo RULES_REMAIN; fi; exit ${'$'}status",
        timeoutMs = 3_000
    )

    private fun buildWatchdogStart(token: String, pid: Int): String {
        val dollar = '$'
        val script = """
            token_file='$WATCHDOG_FILE'
            expected='$token'
            owner_pid='$pid'
            while [ "${dollar}(cat "${dollar}token_file" 2>/dev/null)" = "${dollar}expected" ]; do
              command_line=${dollar}(tr '\000' ' ' < "/proc/${dollar}owner_pid/cmdline" 2>/dev/null)
              case "${dollar}command_line" in
                *$APP_PROCESS_MARKER*) sleep 2 ;;
                *) break ;;
              esac
            done
            if [ "${dollar}(cat "${dollar}token_file" 2>/dev/null)" = "${dollar}expected" ]; then
              $WATCHDOG_CLEANUP
              rm -f "${dollar}token_file"
            fi
        """.trimIndent()
        return "command -v nohup >/dev/null 2>&1 || exit 31; " +
            "printf '%s\\n' '$token' > $WATCHDOG_FILE && chmod 600 $WATCHDOG_FILE || exit 31; " +
            "nohup sh -c ${shellQuote(script)} >/dev/null 2>&1 </dev/null & echo WATCHDOG_READY"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val WATCHDOG_CLEANUP =
        "while iptables -t nat -C OUTPUT -j $NAT_CHAIN 2>/dev/null; do iptables -t nat -D OUTPUT -j $NAT_CHAIN || break; done; " +
            "iptables -t nat -F $NAT_CHAIN 2>/dev/null; iptables -t nat -X $NAT_CHAIN 2>/dev/null; " +
            "while iptables -t filter -C OUTPUT -j $FILTER_CHAIN 2>/dev/null; do iptables -t filter -D OUTPUT -j $FILTER_CHAIN || break; done; " +
            "iptables -t filter -F $FILTER_CHAIN 2>/dev/null; iptables -t filter -X $FILTER_CHAIN 2>/dev/null; " +
            "while ip6tables -t nat -C OUTPUT -j $NAT_CHAIN_V6 2>/dev/null; do ip6tables -t nat -D OUTPUT -j $NAT_CHAIN_V6 || break; done; " +
            "ip6tables -t nat -F $NAT_CHAIN_V6 2>/dev/null; ip6tables -t nat -X $NAT_CHAIN_V6 2>/dev/null; " +
            "while ip6tables -t filter -C OUTPUT -j $FILTER_CHAIN_V6 2>/dev/null; do ip6tables -t filter -D OUTPUT -j $FILTER_CHAIN_V6 || break; done; " +
            "ip6tables -t filter -F $FILTER_CHAIN_V6 2>/dev/null; ip6tables -t filter -X $FILTER_CHAIN_V6 2>/dev/null"
}
