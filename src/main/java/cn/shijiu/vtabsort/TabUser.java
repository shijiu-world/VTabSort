package cn.shijiu.vtabsort;

import java.util.UUID;

/**
 * Tab 里占一个“人位”的身份：真实玩家或 demo 虚拟玩家共用同一套分节/排序逻辑。
 *
 * <p>uuid 为 null = 虚拟玩家（槽位走假条目路径，显示名 = 名字）；
 * 非null = 真实玩家（槽位走真条目路径，显示名归 TAB 管）。
 *
 * @param uuid    真实玩家 UUID；虚拟玩家为 null
 * @param name    名字（排序键；虚拟玩家同时是显示名）
 * @param joinSeq 进代理的先后序号（虚拟玩家 = 出生序号；join 排序模式用）
 */
record TabUser(UUID uuid, String name, long joinSeq) {

    boolean virtual() {
        return uuid == null;
    }
}
