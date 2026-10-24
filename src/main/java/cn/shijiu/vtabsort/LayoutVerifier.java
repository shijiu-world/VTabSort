package cn.shijiu.vtabsort;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 「这个 viewer 客户端里**实际**看到什么」的诊断（{@code /vts verify}）。
 *
 * <p>从 {@link LayoutManager} 里拆出来的：那边负责建模 + 应用 + 对账，这里只做
 * 只读的比对和排版，跟协议/发包完全无关（2026-10-24 review）。
 *
 * <p>26.2 客户端的网格形状完全由「它自己列表里 listed 条目数」推出来
 * （{@code PlayerTabOverlay#extractRenderState}：{@code rows = slots = size}，
 * 再 while 推 cols，列主序）。所以位置不对时先看这里：客户端条目数和插件
 * 模型格数不一样，就是多了/少了条目 —— 多一条整张网格就重排一格。
 */
final class LayoutVerifier {

    private LayoutVerifier() {
    }

    /**
     * 按客户端同一套规则把这个 viewer 的 Tab 列出来。
     *
     * <p>先按 listOrder 降序（同值按旁观/队伍/名字）排，再
     * {@code rows = ceil(n/cols)} 列主序排 —— 和插件建模逐格对齐就能一眼看出谁多谁少。
     */
    static List<String> verify(final Player viewer, final LayoutModel model) {
        final TabList tabList = viewer.getTabList();
        final List<TabListEntry> listed = new ArrayList<>();
        final List<TabListEntry> unlisted = new ArrayList<>();
        for (final TabListEntry entry : tabList.getEntries()) {
            (entry.isListed() ? listed : unlisted).add(entry);
        }
        listed.sort(Comparator
                .comparingInt((TabListEntry e) -> -e.getListOrder())
                .thenComparingInt(e -> e.getGameMode() == 3 ? 1 : 0)
                .thenComparing(e -> e.getProfile().getName(), String.CASE_INSENSITIVE_ORDER));

        // 🔴 客户端只渲染前 80 条（PlayerTabOverlay#getPlayerInfos 的 limit(80)），
        //    而且网格形状是按**截断后**的条数推的 —— 这里必须照做，否则 verify
        //    算出来的列/行跟玩家屏幕上看到的不一样，越验越乱。
        final int listedCount = listed.size();
        final int rendered = Math.min(listedCount, Configuration.CLIENT_MAX_ENTRIES);
        int rows = rendered;
        int cols = 1;
        while (rows > 20) {
            rows = (rendered + ++cols - 1) / cols;
        }
        final int rowsUsed = Math.max(rows, 1);

        final List<String> out = new ArrayList<>();
        out.add("客户端列表：" + listedCount + " 条 listed（另有 " + unlisted.size()
                + " 条 listed=false）"
                + (listedCount > Configuration.CLIENT_MAX_ENTRIES
                        ? "；⚠️ 超过客户端上限 " + Configuration.CLIENT_MAX_ENTRIES + " 条 → 只渲染前 "
                                + rendered + " 条，后面 " + (listedCount - rendered) + " 条谁也看不见"
                        : "")
                + " → 客户端按 " + rendered + " 条算网格：" + cols + " 列 × " + rowsUsed + " 行");
        out.add("插件模型：" + model.slots.size() + " 格（" + model.columnCount + " 列 × "
                + model.rows + " 行）"
                + (rendered == model.slots.size() ? " —— 对得上"
                        : " —— ⚠️ 对不上，客户端会整体错位"));
        for (int i = 0; i < listedCount; i++) {
            final TabListEntry e = listed.get(i);
            final String name = e.getDisplayNameComponent()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                    .orElse(e.getProfile().getName());
            final StringBuilder sb = new StringBuilder("  #").append(i);
            if (i < rendered) {
                sb.append(" 列").append(i / rowsUsed + 1).append(" 行").append(i % rowsUsed + 1);
            } else {
                sb.append(" （超上限，客户端不渲染）");
            }
            sb.append(" order=").append(e.getListOrder())
                    .append("  ").append(name.isEmpty() ? "(空)" : name);
            if (i >= model.slots.size()) {
                sb.append("   ⚠️ 超出插件模型（多余条目）");
            } else {
                final LayoutModel.Slot slot = model.slots.get(i);
                if (slot.player != null) {
                    if (!slot.player.equals(e.getProfile().getId())) {
                        sb.append("   ⚠️ 这格应该是 ").append(slot.name);
                    }
                } else if (!LayoutManager.fakeUuid(i).equals(e.getProfile().getId())) {
                    sb.append("   ⚠️ 这格应该是插件建的假条目");
                }
            }
            out.add(sb.toString());
        }
        for (final TabListEntry e : unlisted) {
            out.add("  (listed=false) " + e.getProfile().getName()
                    + (inModel(model, e.getProfile().getId()) ? "" : "   ⚠️ 多余"));
        }
        return out;
    }

    /** 本轮槽位表里有没有这个 UUID（含隐藏的）。 */
    private static boolean inModel(final LayoutModel model, final UUID id) {
        for (final LayoutModel.Slot s : model.slots) {
            if (id.equals(s.player)) {
                return true;
            }
        }
        return model.hidden.contains(id);
    }
}
