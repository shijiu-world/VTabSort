package cn.shijiu.vtabsort;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一次重算的产物：网格每个槽位的内容 + 每个真实玩家的目标状态。
 * 纯数据（只存 UUID，不抓 Player 引用），所有 viewer 共用同一份。
 */
final class LayoutModel {

    /**
     * 一个槽位：要么真实玩家，要么假条目文本（含空白填充）。
     *
     * @param player  真实玩家的 UUID；null = 假条目槽
     * @param display 假条目的显示名；真实玩家槽为 null（显示名归 TAB 管）
     * @param name    占这个位子的人名（真实玩家 / demo 虚拟玩家）；纯填充槽为 null。
     *                🔴 真玩家的 tab 条目由 TAB 创建，事件瞬间可能还没建出来 ——
     *                那时用同名假条目**临时占位**，否则网格会缺一格、
     *                客户端折行重算 → 整列错位（见 LayoutManager.applyViewer）。
     */
    static final class Slot {
        final UUID player;
        final Component display;
        final String name;

        Slot(final UUID player, final Component display, final String name) {
            this.player = player;
            this.display = display;
            this.name = name;
        }
    }

    /**
     * /vts layout 打印用的分节描述。
     *
     * <p>v3 没有截断了（装不下就续开一列），所以不再有 shown/hidden；
     * 一个子服横跨多列时每列一条 SectionDesc，用 part/parts 标第几段。
     */
    static final class SectionDesc {
        final String server;
        final String display;
        /** 该子服的总人数（列头显示的也是这个数，分段不变）。 */
        final int count;
        /** 本列实际放了多少人。 */
        final int here;
        /** 第几段（1 起）。 */
        final int part;
        /** 一共分了几段（1 = 没分段）。 */
        final int parts;

        SectionDesc(final String server, final String display, final int count,
                    final int here, final int part, final int parts) {
            this.server = server;
            this.display = display;
            this.count = count;
            this.here = here;
            this.part = part;
            this.parts = parts;
        }
    }

    static final class ColumnDesc {
        final List<SectionDesc> sections;

        ColumnDesc(final List<SectionDesc> sections) {
            this.sections = sections;
        }
    }

    /** 列主序的槽位表：槽位号 = 客户端折行后的全局序号。 */
    final List<Slot> slots;
    /** 可见玩家的目标 listOrder（= 槽位号）。 */
    final Map<UUID, Integer> orders;
    /** 需要隐藏（listed=false）的玩家：还没进任何服的（连接中）。 */
    final Set<UUID> hidden;
    final List<ColumnDesc> columns;
    /** 本轮网格行数（自适应，可能小于配置的 rows 上限）。 */
    final int rows;
    final int columnCount;
    /**
     * 列数超过了 max-columns —— 内容真的塞不进「max 列 × rows 行」时的兜底：
     * 保行数（客户端硬约束）、让列数溢出。调大 max-columns / rows，
     * 或者少列几个子服即可消除。
     */
    final boolean exceedsColumns;
    LayoutModel(final List<Slot> slots, final Map<UUID, Integer> orders, final Set<UUID> hidden,
                final List<ColumnDesc> columns, final int rows,
                final boolean exceedsColumns) {
        this.slots = slots;
        this.orders = orders;
        this.hidden = hidden;
        this.columns = columns;
        this.rows = rows;
        this.columnCount = columns.size();
        this.exceedsColumns = exceedsColumns;
    }
}
