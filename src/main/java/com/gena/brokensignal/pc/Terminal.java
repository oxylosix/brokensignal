package com.gena.brokensignal.pc;

import com.gena.brokensignal.Chains;
import com.gena.brokensignal.Director;
import com.gena.brokensignal.HorrorState;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Virtual terminal. Every command is interpreted here against the VFS; nothing is ever
 * passed to a real shell. Returns an optional client effect string.
 */
public final class Terminal {
    private Terminal() {}

    public static String run(ServerPlayer p, Vfs v, HorrorState st, String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty()) {
            return "";
        }
        if (line.length() > 200) {
            line = line.substring(0, 200);
        }
        String cwd = cwd(v);
        v.term(cwd + " $ " + line);
        v.history(line);
        st.inc("pc_cmds");
        String[] a = line.split("\\s+");
        String cmd = a[0].toLowerCase(Locale.ROOT);
        String fx = "";
        switch (cmd) {
            case "help" -> v.term("ls [-a]  cd  pwd  cat  rm  mv  find  history  clear\n"
                    + "date  uptime  whoami  users  ps  kill  ping  decrypt\n"
                    + "open <приложение>  log  hostname  echo  shutdown  exit");
            case "ls", "dir" -> {
                boolean all = a.length > 1 && a[1].startsWith("-a");
                String dir = a.length > (all ? 2 : 1) ? resolve(cwd, a[all ? 2 : 1]) : cwd;
                if (!v.isDir(dir)) {
                    v.term("нет такого каталога");
                    break;
                }
                List<String> names = v.children(dir);
                StringBuilder b = new StringBuilder();
                boolean night = isNight(p);
                for (String n : names) {
                    String path = ("/".equals(dir) ? "" : dir) + "/" + n.replace("/", "");
                    CompoundTag f = v.get(path);
                    boolean hidden = n.startsWith(".") || (f != null && f.getBoolean("h"));
                    if ((hidden && !all) || (f != null && f.getBoolean("n") && !night)) {
                        continue;
                    }
                    b.append(n).append("   ");
                }
                if (all) {
                    st.inc("saw_hidden");
                }
                v.term(b.length() == 0 ? "(пусто)" : b.toString().strip());
            }
            case "cd" -> {
                String dir = a.length > 1 ? resolve(cwd, a[1]) : "/";
                if (v.isDir(dir)) {
                    v.raw().putString("cwd", dir);
                } else {
                    v.term("нет такого каталога");
                }
            }
            case "pwd" -> v.term(cwd);
            case "cat", "type" -> {
                if (a.length < 2) {
                    v.term("cat <файл>");
                    break;
                }
                String path = resolve(cwd, rest(line));
                CompoundTag f = v.get(path);
                if (f == null || (f.getBoolean("n") && !isNight(p))) {
                    v.term("файл не найден");
                } else {
                    f.putInt("r", f.getInt("r") + 1);
                    v.term(f.getBoolean("k") ? ComputerService.corrupt(f.getString("c"), path.hashCode()) : f.getString("c"));
                    Chains.onRead(p, st, v, path, f);
                }
            }
            case "rm", "del" -> {
                if (a.length < 2) {
                    v.term("rm <файл>");
                    break;
                }
                String path = resolve(cwd, rest(line));
                CompoundTag f = v.get(path);
                if (f == null) {
                    v.term("файл не найден");
                } else if (f.getBoolean("lock")) {
                    v.term("отказано: файл используется");
                } else {
                    v.remove(path);
                    st.inc("pc_deleted");
                    v.term("удалён: " + Vfs.name(path));
                    Chains.onDelete(p, st, v, path, f);
                }
            }
            case "mv", "rename" -> {
                if (a.length < 3) {
                    v.term("mv <из> <в>");
                } else if (!v.rename(resolve(cwd, a[1]), resolve(cwd, a[2]))) {
                    v.term("файл не найден");
                }
            }
            case "find", "search" -> {
                String q = a.length > 1 ? rest(line).toLowerCase(Locale.ROOT) : "";
                StringBuilder b = new StringBuilder();
                int n = 0;
                for (String path : v.paths()) {
                    CompoundTag f = v.get(path);
                    if (f != null && (path.toLowerCase(Locale.ROOT).contains(q) || f.getString("c").toLowerCase(Locale.ROOT).contains(q)) && n < 12) {
                        b.append(path).append('\n');
                        n++;
                    }
                }
                v.term(n == 0 ? "ничего не найдено" : b.toString().stripTrailing());
                Chains.onCmd(p, st, v, "find", q);
            }
            case "history" -> {
                StringBuilder b = new StringBuilder();
                int i = 1;
                for (Tag t : v.strings("hist")) {
                    b.append(String.format(Locale.ROOT, "%3d  %s%n", i++, t.getAsString()));
                }
                v.term(b.toString().stripTrailing());
                if (!v.strings("ghost").isEmpty()) {
                    st.inc("saw_ghost_history");
                }
            }
            case "clear", "cls" -> v.strings("term").clear();
            case "date", "time" -> v.term(ComputerService.clock(p));
            case "uptime" -> v.term("в работе: " + (st.now() / 1200) + " мин");
            case "whoami" -> {
                if (Director.phase(p) >= 6 && p.getRandom().nextInt(6) == 0 && !st.flag("whoami_odd")) {
                    st.setFlag("whoami_odd", true);
                    v.term("гость");
                    fx = "flicker";
                } else {
                    v.term(p.getGameProfile().getName());
                }
            }
            case "users", "who" -> {
                v.term(p.getGameProfile().getName() + "   активен");
                if (Director.phase(p) >= 4) {
                    String last = st.time("lastWorldEvent") > 0
                            ? ComputerService.clock(p.serverLevel(), -(st.now() - st.time("lastWorldEvent")))
                            : ComputerService.clock(p.serverLevel(), -9000);
                    v.term("гость   последний вход: " + last);
                }
            }
            case "ps" -> {
                v.term("  1  init\n 14  sensors\n 22  term");
                if (Director.phase(p) >= 4 && !st.flag("killed_listen")) {
                    v.term(" 31  listen");
                }
                if (Director.phase(p) >= 6) {
                    v.term(" ??  " + (st.flag("killed_listen") ? "listen" : "—"));
                }
            }
            case "kill" -> {
                String target = a.length > 1 ? a[1] : "";
                if (target.equals("31") || target.equals("listen")) {
                    if (st.flag("killed_listen")) {
                        v.term("нет такого процесса");
                    } else {
                        st.setFlag("killed_listen", true);
                        v.term("завершён: listen");
                        Chains.onCmd(p, st, v, "kill", "listen");
                    }
                } else if (target.equals("1") || target.equals("22")) {
                    v.term("отказано");
                } else {
                    v.term("нет такого процесса");
                }
            }
            case "ping" -> {
                String host = a.length > 1 ? a[1] : "localhost";
                if (host.equals("home") || host.equals("дом")) {
                    BlockPos home = p.getRespawnPosition();
                    v.term(home == null ? "узел недоступен" : "ответ от дома: ~" + (int) Math.sqrt(home.distSqr(p.blockPosition())) + " блоков");
                } else if (host.equals("localhost") || host.equals("127.0.0.1")) {
                    v.term("64 байта от 127.0.0.1: время=0.1 мс\n64 байта от 127.0.0.1: время=0.1 мс");
                    if (Director.phase(p) >= 5 && p.getRandom().nextInt(3) == 0) {
                        v.term("64 байта от 127.0.0.1: время=9841 мс");
                    }
                } else {
                    v.term("сеть недоступна");
                }
            }
            case "decrypt" -> {
                if (a.length < 3) {
                    v.term("decrypt <файл> <ключ>");
                    break;
                }
                String path = resolve(cwd, a[1]);
                CompoundTag f = v.get(path);
                if (f == null) {
                    v.term("файл не найден");
                } else if (!f.contains("key")) {
                    v.term("файл не зашифрован");
                } else if (!f.getString("key").equals(a[2])) {
                    st.inc("decrypt_fail");
                    v.term("неверный ключ");
                } else {
                    f.putString("c", f.getString("plain"));
                    f.putBoolean("k", false);
                    f.remove("key");
                    v.term("готово.");
                    Chains.onCmd(p, st, v, "decrypt", path);
                }
            }
            case "open", "run" -> {
                String app = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (app.equals("listen") && st.flag("listen_hint")) {
                    if (v.unlock("listen")) {
                        v.term("установлено: listen");
                    }
                    fx = "app:listen";
                } else if (v.appUnlocked(app) || List.of("files", "term", "msgs", "notes", "logs", "settings").contains(app)) {
                    fx = "app:" + app;
                } else {
                    v.term("приложение не найдено");
                }
            }
            case "log", "logs" -> {
                List<Tag> logs = v.strings("logs");
                int from = Math.max(0, logs.size() - 8);
                for (int i = from; i < logs.size(); i++) {
                    v.term(logs.get(i).getAsString());
                }
            }
            case "hostname" -> v.term(ComputerService.hostname(p));
            case "echo" -> v.term(rest(line));
            case "sudo" -> v.term("нет.");
            case "shutdown", "poweroff" -> {
                v.term("завершение работы...");
                st.inc("pc_shutdowns");
                Chains.onCmd(p, st, v, "shutdown", "");
                fx = "close";
            }
            case "exit", "logout" -> fx = "close";
            default -> {
                v.term(cmd + ": команда не найдена");
                Chains.onCmd(p, st, v, cmd, rest(line));
            }
        }
        Director.LOG.debug("[BrokenSignal] terminal {}: {}", p.getGameProfile().getName(), line);
        return fx;
    }

    private static String rest(String line) {
        int i = line.indexOf(' ');
        return i < 0 ? "" : line.substring(i + 1).strip();
    }

    static String cwd(Vfs v) {
        String c = v.raw().getString("cwd");
        return c.isEmpty() ? "/" : c;
    }

    static boolean isNight(ServerPlayer p) {
        long d = p.serverLevel().getDayTime() % 24000L;
        return d >= 13000 && d < 23000;
    }

    static String resolve(String cwd, String arg) {
        String s = arg.strip();
        if (s.startsWith("/")) {
            return normalizeDots(s);
        }
        return normalizeDots(("/".equals(cwd) ? "" : cwd) + "/" + s);
    }

    private static String normalizeDots(String path) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(part);
            }
        }
        return "/" + String.join("/", parts);
    }
}
