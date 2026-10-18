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

    /** 一个槽位：要么真实玩家，要么假条目文本（含空白填充）。 */
    static final class Slot {
        /** 真实玩家的 UUID；null = 假条目槽。 */
        final UUID player;
        /** 假条目的显示名；玩家槽为 null。 */
        final Component display;

        Slot(final UUID player, final Component display) {
            this.player = player;
            this.display = display;
        }
    }

    /** /vts layout 打印用的分节描述。 */
    static final class SectionDesc {
        final String server;
        final String display;
        final int count;
        final int shown;
        final int hidden;

        SectionDesc(final String server, final String display, final int count,
                    final int shown, final int hidden) {
            this.server = server;
            this.display = display;
            this.count = count;
            this.shown = shown;
            this.hidden = hidden;
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
    /** 需要隐藏（listed=false）的玩家：截断溢出的 + 还没进任何服的。 */
    final Set<UUID> hidden;
    final List<ColumnDesc> columns;
    /** 本轮网格行数（自适应，可能小于配置的 rows 上限）。 */
    final int rows;
    final int columnCount;

    LayoutModel(final List<Slot> slots, final Map<UUID, Integer> orders, final Set<UUID> hidden,
                final List<ColumnDesc> columns, final int rows) {
        this.slots = slots;
        this.orders = orders;
        this.hidden = hidden;
        this.columns = columns;
        this.rows = rows;
        this.columnCount = columns.size();
    }
}
