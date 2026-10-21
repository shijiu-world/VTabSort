package cn.shijiu.vtabsort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * demo 模拟器：在 layout 网格里注入虚拟玩家，让你在真实客户端里看
 * 玩家进出/换服时网格怎么动（mineflayer 连不上 26.2 代理，这是唯一正路）。
 *
 * <p>虚拟玩家没有连接、没有 UUID —— 在 LayoutManager 里就是 TabUser(uuid=null)，
 * 渲染走假条目路径、显示名 = 名字，和真人观感一致；分列/排序/续列走的
 * 全是真实代码路径，看到的就是最终效果。
 *
 * <p>两种玩法（{@code /vts demo}）：
 * <ul>
 *   <li><b>固定</b>：{@code lobby:5 survival:12} —— 指定各服人数；</li>
 *   <li><b>流动</b>：{@code flow [总人数]} —— 每 2 秒随机进出/换服，
 *       0 人的服整列消失、人多的服自动排前、超高自动续列，全都能看到。</li>
 * </ul>
 *
 * <p>线程模型：所有可变状态 synchronized 本对象；快照返回深拷贝。
 * 名字带出生序号（joinSeq），join 排序模式下虚拟玩家按“出生”排在真人后面。
 */
final class DemoSim {

    /** 单服人数上限（防手滑把自己埋了）。 */
    static final int PER_SERVER_CAP = 200;

    /** 一个虚拟玩家：名字 + 出生序号。 */
    record VUser(String name, long seq) {
    }

    private static final String[] FIRST = {
            "Frost", "Iron", "Night", "Amber", "Cloud", "Shadow", "Pine", "River",
            "Storm", "Dawn", "Maple", "Stone", "Echo", "Lunar", "Ember", "Bloom",
            "Silent", "Swift", "Bright", "Mist"
    };
    private static final String[] SECOND = {
            "Fox", "Owl", "Bear", "Hawk", "Deer", "Ray", "Fin", "Jay",
            "Kit", "Moth", "Paw", "Tide", "Wind", "Leaf", "Song", "Gem"
    };

    private final Map<String, List<VUser>> byServer = new LinkedHashMap<>();
    private final Random random = new Random();
    private final AtomicLong birthCounter = new AtomicLong();

    /** 流动模式标记（task 由 VTabSort 持有和取消，这里只记状态）。 */
    private volatile boolean flow;

    synchronized boolean isActive() {
        return !byServer.isEmpty();
    }

    synchronized boolean isFlow() {
        return flow;
    }

    /** 当前虚拟玩家总数（auto 模式的翻面判定也要把它算进去）。 */
    synchronized int virtualCount() {
        int n = 0;
        for (final List<VUser> l : byServer.values()) {
            n += l.size();
        }
        return n;
    }

    /** 深拷贝快照：LayoutManager.build 的输入。 */
    synchronized Map<String, List<TabUser>> snapshot() {
        final Map<String, List<TabUser>> out = new LinkedHashMap<>();
        for (final Map.Entry<String, List<VUser>> e : byServer.entrySet()) {
            final List<TabUser> l = new ArrayList<>(e.getValue().size());
            for (final VUser v : e.getValue()) {
                l.add(new TabUser(null, v.name(), v.seq()));
            }
            out.put(e.getKey(), l);
        }
        return out;
    }

    /** 状态行（/vts demo 无参数时打印）。 */
    synchronized String describe() {
        if (byServer.isEmpty()) {
            return "关";
        }
        final StringBuilder sb = new StringBuilder(flow ? "流动" : "固定").append("：");
        for (final Map.Entry<String, List<VUser>> e : byServer.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue().size()).append(' ');
        }
        return sb.toString().trim();
    }

    synchronized void clear() {
        byServer.clear();
        flow = false;
    }

    /** 固定模式：各服人数直接指定。 */
    synchronized void setFixed(final Map<String, Integer> counts) {
        byServer.clear();
        flow = false;
        for (final Map.Entry<String, Integer> e : counts.entrySet()) {
            final List<VUser> l = new ArrayList<>();
            for (int i = 0; i < Math.min(e.getValue(), PER_SERVER_CAP); i++) {
                l.add(spawn(l));
            }
            byServer.put(e.getKey(), l);
        }
    }

    /** 流动模式：把 total 随机摊到池子里的服（部分服可能 0 人 = 列不出现）。 */
    synchronized void startFlow(final List<String> pool, final int total) {
        byServer.clear();
        flow = true;
        int remaining = total;
        for (final String s : pool) {
            if (remaining <= 0) {
                break;
            }
            // 随机分一块（至少留够后面每个服还能拿 0~2 人，摊得开一点）
            final int take = Math.min(remaining, random.nextInt(3) + Math.max(0, remaining / Math.max(1, pool.size() / 2)));
            final List<VUser> l = new ArrayList<>();
            for (int i = 0; i < take; i++) {
                l.add(spawn(l));
            }
            byServer.put(s, l);
            remaining -= take;
        }
    }

    /**
     * 流动一拍（每 ~2 秒一次，由 VTabSort 的定时任务驱动）：
     * 70% 随机某服 ±1 人；15% 一个人从 A 服搬到 B 服（换服）；15% 某服 ±3 人（小高峰）。
     */
    synchronized void tick(final List<String> pool) {
        if (!flow || byServer.isEmpty()) {
            return;
        }
        final List<String> active = new ArrayList<>(byServer.keySet());
        final int roll = random.nextInt(100);
        if (roll < 70) {
            final String s = active.get(random.nextInt(active.size()));
            adjust(s, random.nextBoolean() ? 1 : -1);
        } else if (roll < 85 && active.size() >= 1) {
            // 换服：从一个人多的服搬一个去另一个服（保持名字和出生序号不变）
            String from = active.get(random.nextInt(active.size()));
            if (byServer.get(from).isEmpty()) {
                from = active.stream().filter(s -> !byServer.get(s).isEmpty()).findFirst().orElse(null);
            }
            if (from != null && !byServer.get(from).isEmpty()) {
                final String to = pool.get(random.nextInt(pool.size()));
                final List<VUser> src = byServer.get(from);
                final VUser mover = src.remove(src.size() - 1);
                byServer.computeIfAbsent(to, k -> new ArrayList<>()).add(mover);
                if (byServer.get(from).isEmpty()) {
                    byServer.remove(from); // 0 人 = 列消失
                }
            }
        } else {
            final String s = active.get(random.nextInt(active.size()));
            adjust(s, random.nextBoolean() ? 3 : -3);
        }
        // 顺手清掉 0 人的服（列消失）
        byServer.values().removeIf(List::isEmpty);
    }

    private void adjust(final String server, final int delta) {
        final List<VUser> l = byServer.computeIfAbsent(server, k -> new ArrayList<>());
        if (delta > 0) {
            for (int i = 0; i < delta && l.size() < PER_SERVER_CAP; i++) {
                l.add(spawn(l));
            }
        } else {
            for (int i = 0; i < -delta && !l.isEmpty(); i++) {
                l.remove(l.size() - 1);
            }
        }
    }

    /** 生成一个不与该服现有名字冲突的随机名字（形如 NightOwl / EchoJay7）。 */
    private VUser spawn(final List<VUser> existing) {
        String name = FIRST[random.nextInt(FIRST.length)] + SECOND[random.nextInt(SECOND.length)];
        if (random.nextInt(5) == 0) {
            name += random.nextInt(100);
        }
        // 同服去重：撞了就缀个序号，总归能出来
        while (containsName(existing, name)) {
            name = name + "_";
        }
        return new VUser(name, birthCounter.incrementAndGet());
    }

    private static boolean containsName(final List<VUser> list, final String name) {
        for (final VUser v : list) {
            if (v.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
