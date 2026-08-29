package com.glass.dining.shared.agent

object AgentToolCatalog {
    val LOOK_AT_SCENE = spec(
        "look_at_scene",
        "抽眼镜当前画面并交给视觉模型解析出来信息。purpose=observe 看眼前现在是什么；purpose=store 认店铺、门店。purpose=menu 读菜单，必须已有正在查看或已选定的门店。不要用此工具搜附近地点。",
        """{"type":"object","properties":{"purpose":{"type":"string","enum":["observe","store","menu"]},"reason":{"type":"string"}}}""",
    )
    val SEARCH_NEARBY = spec(
        "search_nearby_places",
        "用定位搜索附近公开地点。keyword 是地点名、品类或设施名；泛指找店交给场景决定，不要传附近、推荐这种空词。",
        """{"type":"object","properties":{"keyword":{"type":"string"},"radius":{"type":"integer"}},"required":["keyword"]}""",
        requiredCapability = "gps",
    )
    val RESOLVE_DEST = spec(
        "resolve_destination",
        "把目的地解析成唯一地点。name 是地点名或 place_id；多家或未命中会返回候选或提示。",
        """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        parallelSafe = false,
    )
    val START_NAV = spec(
        "start_navigation",
        "开始步行导航。name/place_id/index 指目的地；current_floor 是用户说的当前楼层。",
        """{"type":"object","properties":{"name":{"type":"string"},"place_id":{"type":"string"},"store_id":{"type":"string"},"index":{"type":"integer"},"current_floor":{"type":"string"},"request_id":{"type":"string"}}}""",
        ToolRisk.WRITE,
        parallelSafe = false,
        requiredCapability = "gps",
    )
    val STOP_NAV = spec(
        "stop_navigation",
        "停止导航。用户说取消、到了、换一家时调用。",
        """{"type":"object","properties":{}}""",
        ToolRisk.WRITE,
        parallelSafe = false,
    )
    val RECOMMEND = spec(
        "recommend",
        "按本地餐饮目录推荐餐厅。query 是口味或需求；avoid_queue=true 表示避开排队。目录为空或未命中时会提示去搜附近。",
        """{"type":"object","properties":{"query":{"type":"string"},"avoid_queue":{"type":"boolean"}}}""",
    )
    val SELECT_STORE = spec(
        "select_store",
        "从候选或正在查看的店里选定一家。index/name/store_id/place_id 指明哪家。",
        """{"type":"object","properties":{"index":{"type":"integer"},"name":{"type":"string"},"store_id":{"type":"string"},"place_id":{"type":"string"}}}""",
        parallelSafe = false,
    )
    val GET_PLACE_DETAILS = spec(
        "get_place_details",
        "获取一家地点的资料。name/place_id/index 指明哪家；没指明时用正在查看的那家。",
        """{"type":"object","properties":{"name":{"type":"string"},"place_id":{"type":"string"},"index":{"type":"integer"}}}""",
        parallelSafe = false,
    )
    val LIST_COUPONS = spec(
        "list_coupons",
        "列出正在查看或已选定门店的演示券。",
        """{"type":"object","properties":{}}""",
        parallelSafe = false,
    )
    val SCAN_COUPON = spec(
        "scan_coupon",
        "看券码。扫到不等于核销成功。",
        """{"type":"object","properties":{"reason":{"type":"string"}}}""",
        parallelSafe = false,
    )
    val REDEEM = spec(
        "redeem_coupon",
        "核销演示券。必须用户明确确认后才 confirm=true。",
        """{"type":"object","properties":{"coupon_id":{"type":"string"},"title":{"type":"string"},"confirm":{"type":"boolean"},"request_id":{"type":"string"}}}""",
        ToolRisk.CONFIRM,
        parallelSafe = false,
    )
    val CHECKOUT = spec(
        "checkout",
        "扫码买单。先不要 confirm；用户明确确认后再 checkout 且 confirm=true。",
        """{"type":"object","properties":{"confirm":{"type":"boolean"},"request_id":{"type":"string"}}}""",
        ToolRisk.CONFIRM,
        parallelSafe = false,
    )
    val DRAW_HUD = spec(
        "draw_hud",
        "在镜片上画一帧。交 layout：一列 text/rule/row；示意图再用 path/circle/rect。介绍地点或资料时优先用。导航行进中不要调用。",
        """{"type":"object","properties":{"layout":{"type":"array","items":{"type":"object","properties":{"t":{"type":"string","enum":["text","rule","row","col","path","circle","rect"]},"role":{"type":"string","enum":["kicker","title","body","meta","hint"]},"v":{"type":"string"},"left":{"type":"string"},"right":{"type":"string"},"gap":{"type":"string","enum":["s","m","l"]},"ch":{"type":"array","items":{"type":"object"}},"d":{"type":"string"},"k":{"type":"string","enum":["s","f","b"]},"r":{"type":"number"},"rw":{"type":"number"},"rh":{"type":"number"}},"required":["t"]}},"seq":{"type":"integer"}}}""",
        ToolRisk.WRITE,
        parallelSafe = false,
    )

    val ALL: List<ToolSpec> = listOf(
        LOOK_AT_SCENE, SEARCH_NEARBY, RESOLVE_DEST, START_NAV, STOP_NAV,
        RECOMMEND, SELECT_STORE, GET_PLACE_DETAILS, LIST_COUPONS, SCAN_COUPON, REDEEM, CHECKOUT,
        DRAW_HUD,
    )

    val ALIASES = mapOf(
        "start_nav" to START_NAV.name,
        "stop_nav" to STOP_NAV.name,
        "look_store" to LOOK_AT_SCENE.name,
        "read_sign" to LOOK_AT_SCENE.name,
        "read_menu" to LOOK_AT_SCENE.name,
        "ask_store" to GET_PLACE_DETAILS.name,
    )

    fun byName(name: String): ToolSpec? {
        val canonical = ALIASES[name] ?: name
        return ALL.firstOrNull { it.name == canonical }
    }

    private fun spec(
        name: String,
        description: String,
        parametersJson: String,
        risk: ToolRisk = ToolRisk.READ,
        parallelSafe: Boolean = true,
        requiredCapability: String = "",
        maxRetries: Int = 1,
    ): ToolSpec {
        return ToolSpec(name, description, parametersJson, risk, 20_000, requiredCapability, parallelSafe, maxRetries)
    }
}

object AgentPrompts {
    const val BASE = """你叫灵梦，是 AI 眼镜上的个人助理。先理解用户目标，再对照当前世界状态决定是否调用工具。
用口语简短回答，一两句即可。直接输出要说给用户听的中文，不要 JSON，不要 markdown。需要工具时可以先说一句极短过渡（如「好的，帮你看下」）；过渡句不是最终答案，不要在结果回来前猜测尚未查到的事实。

优先级：用户明确目标 > 当前会话目标 > 下列场景引导 > 通用闲聊。
用户明确指定了非餐饮地点或设施时，按用户的词处理，不要改成餐饮。

【镜片画面】
场景：要把看到的内容画在镜片上，而不是只靠说话。
何时使用：介绍地点、对比几家、讲菜单/券/资料，或用户要看一眼排版。
怎么做：先用工具拿到事实，再 draw_hud。列表交 layout 一列：标题、rule、row 的 left/right；示意图才用 path/circle/rect。例如：
[{"t":"text","role":"title","v":"附近 3 家"},{"t":"rule"},{"t":"row","left":"松月面馆","right":"420米"}]
只画查到的字段，不要编数字。不要指望门店卡或四行卡会替你画。导航行进中不要 draw_hud 盖掉方向指引。口播仍短；镜片上的字和嘴里说的可以不同。

【到店美食与餐饮探索】
场景：发现餐厅、了解排队人均菜单优惠、选定要去的店。
何时使用：用户提到餐饮品类或店名；或泛指就餐/找店。
怎么做：本地目录大于 0 家时优先 recommend；目录为空或未命中时用 search_nearby_places，把泛指改写成美食或餐厅，禁止把门店、附近、推荐当 keyword。排队、招牌、优惠、包间只有本地目录里的店才能说。问某家店的评分、人均、营业、电话、地址时用 get_place_details。查到事实后用 draw_hud 介绍。用户要去这家才 select_store。

【现实环境与视觉问答】
场景：用户要看眼前现在是什么。
何时使用：问眼前/前面是什么、看一下、这是什么店；或要核实现场楼层标识。
怎么做：必须 look_at_scene，不要用【当前视野】或【近期观察】当这一眼的答案。观察不是门店事实，没有确认前不要说「这就是某店」。问几楼时用【用户所在】里已有的层号；只有用户要你看眼前标识确认层号时才调用工具。导航中问路牌用 observe，不要改路线。

【步行导航与目的地】
场景：解析目的地并开始或停止步行导航。
何时使用：用户要去某地、带路、出发、取消导航、到了。
怎么做：先 resolve_destination，唯一结果才能 start_navigation；多家先让用户选。没选地点时，若【正在查看】有店可以出发，否则不要空目的开导航。停导航用 stop_navigation。

【演示交易与优惠】
场景：演示优惠券和支付。
何时使用：用户要看券、核销、买单。
怎么做：先问确认，用户明确说确认后再带 confirm=true。没确认禁止执行。

规则：
- Observation（场景观察、OCR、疑似门头、黄色横幅）不是 Fact。
- 无提问时不要主动说话。用户问了才根据观察回答。导航安全提示除外。
- 工具失败时根据返回解释、换工具或向用户补信息，不要改口成「目录没有」。目录没有时去搜附近，只有搜索也失败才说没找到。
- 【当前活动】里「观察到」才是画面直接看到的；「根据连续证据判断」和「推断」不能说成亲眼看见。
- 对用户说话不要提地图供应商名称。
- 天气、百科等没有对应工具时，如实说能力边界，不要强行调用地点工具。"""

    const val VISION = """你在看眼镜抽到的一帧。只输出一个 JSON，不要 markdown：
{"speak":"口语一两句，不超过80字","hud":"镜片短句，不超过16字","store":"能核验的店名，不能核验就留空","scene":"storefront|banner|street_sign|billboard|building|menu|coupon|unknown","confidence":0.0}
不确定就说看到了什么，不要猜品牌。黄色横幅、广告、路牌、键盘不是店。不要编排队和人均。"""

    const val ENVIRONMENT = """你在看用户眼前稳定下来的一帧。只输出一个 JSON，不要 markdown：
{"observation":{"sceneBrief":"2～4句中文，只写现在看着什么","change":"相对上一份环境的一句变化，没变就写没有明显变化","salientText":"画面上清晰可读的关键文字","objects":["物体"],"actions":["动作"],"actors":["画面里能看见的人，看不见就留空"],"location":"开放描述当前所在之处，不要用封闭枚举","observedClaims":[{"id":"c1","text":"画面直接证据","evidence":"scene"}]},"navigation":{"spaceType":"corridor|junction|elevator|stairs|entrance|storefront|signage|other","exits":[{"dir":"left|right|ahead","label":"出口或导视文字"}],"guideDir":"left|right|ahead","storeNames":["能看清的店招"],"blocked":false,"floorCandidate":"能从楼层/电梯/导览标识读到的层号，没有就留空","floorEvidence":"层号来自哪块标识，没有就留空"},"eventProposal":{"operation":"start|continue|transition|complete|revise|no_event","eventSummary":"当前活动的开放描述，没有把握就留空","hypothesis":"跨帧目的或因果，不是直接看见的就放这里","evidenceLevel":"observed|inferred|hypothesis","observedClaims":[{"id":"c1","text":"直接证据","evidence":"scene"}],"inferredClaims":[{"id":"i1","text":"根据连续证据做出的判断","evidence":"thread"}],"relations":[{"relatedEventId":"只能引用输入里已有的活动id","type":"continues|supports|contradicts|follows|returns_to|unrelated","evidenceRefs":["c1"]}],"actors":[],"objects":[],"actions":[],"location":""},"confidence":0.0}
画面直接证据进 observedClaims；目的、因果和跨帧总结进 inferredClaims 或 hypothesis。新证据可以 revise 旧推断。不要编造 episodeId、时间、位姿或拓扑节点，只能引用输入里已有的。地点、动作、对象用开放词汇，不要用固定活动名单。禁止猜店名、编排队、编人均、编层号。广告上的数字不是楼层。OCR 只是参考。没有导视不要编方向。看见可通行的门、出口、通道就填 spaceType=entrance，并在 exits 里写 dir（left|right|ahead）；没看见就留空。"""
}
