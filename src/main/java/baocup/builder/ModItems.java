package baocup.builder;

import baocup.builder.item.BuildCommanderItem;
import baocup.builder.item.ExternalBuildWandItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuilderMod.MODID);

    /** 内部 Agent 物品：启动游戏内 LLM Agent（建造 / 修改 / Plan 对话 / 视觉审查）。 */
    public static final DeferredItem<BuildCommanderItem> BUILD_COMMANDER =
            ITEMS.registerItem("build_commander", props -> new BuildCommanderItem(props.stacksTo(1)));

    /** 外部 Agent 物品：只负责选坐标 + 让 MCP 读到 taskId，不消耗游戏内模型 token。 */
    public static final DeferredItem<ExternalBuildWandItem> EXTERNAL_BUILD_WAND =
            ITEMS.registerItem("external_build_wand",
                    props -> new ExternalBuildWandItem(props.stacksTo(1)));

    private ModItems() {
    }
}
