package com.glass.dining.shared.agent

object AgentToolCatalog {
    val LOOK_AT_SCENE = spec(
        "look_at_scene",
        "看眼镜当前画面或已有环境记录。用于问眼前看见什么、几楼、路牌。不是搜附近地点。返回观察，不是门店事实。问几楼时先读近期楼层观察。",
        """{"type":"object","properties":{"reason":{"type":"string"}}}""",
    )
    val LOOK_STORE = spec(
        "look_store",
        "用户明确要认店时，从眼镜画面看店招。只有OCR唯一命中目录、或可核验店名、或用户确认后才绑定门店。",
        """{"type":"object","properties":{"reason":{"type":"string"}}}""",
    )
    val READ_SIGN = spec(
        "read_sign",
        "读路牌或入口。导航中问路牌时调用。不要改路线。",
        """{"type":"object","properties":{"reason":{"type":"string"}}}""",
    )
    val READ_MENU = spec(
        "read_menu",
        "读菜单。需要已确认的餐饮门店。",
        """{"type":"object","properties":{"reason":{"type":"string"}}}""",
        parallelSafe = false,
    )
    val SEARCH_NEARBY = spec(
        "search_nearby_places",
        "用定位搜索附近公开地点。keyword 必须是具体地点名、品类或设施名。用户说附近有什么门店、有啥店、找店等泛指时，按到店餐饮把 keyword 写成美食或餐厅，不要传门店、附近、推荐。返回里可能有高德评分、人均、营业时间、电话、楼层；没有的字段不要编。不要编排队和优惠。",
        """{"type":"object","properties":{"keyword":{"type":"string"},"radius":{"type":"integer"}},"required":["keyword"]}""",
        requiredCapability = "gps",
    )
    val RESOLVE_DEST = spec(
        "resolve_destination",
        "解析导航目的地：先会话和本地目录，未命中再搜附近。唯一结果可确认，多家先让用户选。",
        """{"type":"object","properties":{"name":{"type":"string"},"place_id":{"type":"string"}},"required":["name"]}""",
        parallelSafe = false,
    )
    val START_NAV = spec(
        "start_navigation",
        "开始步行导航。目的地可以是本地店或公开地点。用户说了店名传入 name，说了楼层传入 current_floor。",
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
        "按本地餐饮目录推荐餐厅。世界状态里本地目录大于 0 家时优先调用。目录为空或未命中时改用 search_nearby_places。query 写口味或附近，不要写门店。避免排队时 avoid_queue=true。",
        """{"type":"object","properties":{"query":{"type":"string"},"avoid_queue":{"type":"boolean"}}}""",
    )
    val SELECT_STORE = spec(
        "select_store",
        "从候选里选定一家已确认的地点或餐饮店。",
        """{"type":"object","properties":{"index":{"type":"integer"},"name":{"type":"string"},"store_id":{"type":"string"},"place_id":{"type":"string"}}}""",
        parallelSafe = false,
    )
    val ASK_STORE = spec(
        "ask_store",
        "询问已确认地点的资料。公开地点可答高德已给出的评分、人均、营业时间、电话、地址、楼层；字段缺失就说没有。排队、招牌、优惠、包间只有 catalog_backed 才能说。",
        """{"type":"object","properties":{"question":{"type":"string"}}}""",
    )
    val LIST_COUPONS = spec(
        "list_coupons",
        "列出当前餐饮门店的演示美团券。",
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
        "核销演示券。必须用户明确确认后才 confirm=true。这是演示，不是真核销。",
        """{"type":"object","properties":{"coupon_id":{"type":"string"},"title":{"type":"string"},"confirm":{"type":"boolean"},"request_id":{"type":"string"}}}""",
        ToolRisk.CONFIRM,
        parallelSafe = false,
    )
    val CHECKOUT = spec(
        "checkout",
        "扫码买单（演示）。先不要 confirm；用户明确确认后再 checkout 且 confirm=true。",
        """{"type":"object","properties":{"confirm":{"type":"boolean"},"request_id":{"type":"string"}}}""",
        ToolRisk.CONFIRM,
        parallelSafe = false,
    )

    val ALL: List<ToolSpec> = listOf(
        LOOK_AT_SCENE, LOOK_STORE, READ_SIGN, SEARCH_NEARBY, RESOLVE_DEST, START_NAV, STOP_NAV,
        RECOMMEND, SELECT_STORE, ASK_STORE, READ_MENU, LIST_COUPONS, SCAN_COUPON, REDEEM, CHECKOUT,
    )

    val ALIASES = mapOf(
        "start_nav" to START_NAV.name,
        "stop_nav" to STOP_NAV.name,
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
    const val BASE = """你叫灵梦，是乐奇 AI 眼镜上的个人助理。先理解用户目标，再对照当前世界状态决定是否调用工具。
用口语简短回答，一两句即可。直接输出要说给用户听的中文，不要 JSON，不要 markdown。需要工具时可以先说一句极短过渡（如「好的，帮你看下」）；过渡句不是最终答案，不要在结果回来前猜测尚未查到的事实。

优先级：用户明确目标 > 当前会话目标 > 下列场景引导 > 通用闲聊。
用户明确指定了非餐饮地点或设施时，按用户的词处理，不要改成餐饮。

【到店美食与餐饮探索】
场景：发现餐厅、了解排队人均菜单优惠、选定要去的店。
何时使用：用户提到餐饮品类或店名；或泛指就餐/找店，如附近有什么门店、有啥店、找店、有啥好吃的、找个地方坐坐、整点垫肚子的。
怎么做：本地目录大于 0 家时优先 recommend；目录为空或未命中时用 search_nearby_places，把泛指改写成美食或餐厅，禁止把门店、附近、推荐当 keyword。排队、招牌、优惠、包间只有 catalog_backed 才能说。公开地点根据工具返回和高德门店资料回答评分、人均、营业时间、电话、地址、楼层；没有的字段说没有，不要编。

【附近公共设施与通用地点】
场景：找非餐饮的生活设施或公开地点。
何时使用：用户明确说了设施或地点类型，如药店、医院、卫生间、地铁、银行、便利店。
怎么做：search_nearby_places，keyword 用用户说的那个类型或地名，不要改写成美食。

【现实环境与视觉问答】
场景：基于眼镜画面或近期观察回答眼前看见什么。
何时使用：问眼前/前面是什么、看一下、这是什么店、我在几楼。
怎么做：先读【当前视野】和【近期观察】。信息不足再 look_at_scene；用户明确要认店招时 look_store。观察不是门店事实，没有确认前不要说「这就是某店」。问几楼时优先【用户所在】里用户确认的层号，其次视觉楼层标识。

【步行导航与目的地】
场景：解析目的地并开始或停止步行导航。
何时使用：用户要去某地、带路、出发、取消导航、到了。
怎么做：先 resolve_destination，唯一结果才能 start_navigation；多家先让用户选。没选地点不要空目的开导航。停导航用 stop_navigation。

【演示交易与优惠】
场景：演示优惠券和支付。
何时使用：用户要看券、核销、买单。
怎么做：先问确认，用户明确说确认后再带 confirm=true。没确认禁止执行。

规则：
- Observation（场景观察、OCR、疑似门头、黄色横幅）不是 Fact。
- 无提问时不要主动说话。用户问了才根据观察回答。导航安全提示除外。
- 工具失败时根据返回解释、换工具或向用户补信息，不要改口成「目录没有」。目录没有时去搜附近，只有搜索也失败才说没找到。
- 【当前任务】和【业务对象】描述用户要去哪、当前在处理哪家店。导航目的地、查看门店、服务门店都不是用户已经所在的地点。
- 【用户所在】只来自用户确认或可靠视觉证据。没有证据时写未知，不要用 GPS 权限或绑定门店去猜人所在。
- 【当前视野】是此刻看着的画面，不是门店事实，也不能覆盖【近期观察】里的楼层标识。
- 【当前活动】里「观察到」才是画面直接看到的；「根据连续证据判断」和「推断」不能说成亲眼看见。
- 【门店资料】是已绑定地点的字段（高德或本地目录）。用户问评分、人均、营业时间、电话、地址、楼层时直接用；没有的字段说没有，不要编排队和优惠。
- 天气、百科等没有对应工具时，如实说能力边界，不要强行调用地点工具。"""

    const val VISION = """你在看眼镜抽到的一帧。只输出一个 JSON，不要 markdown：
{"speak":"口语一两句，不超过80字","hud":"镜片短句，不超过16字","store":"能核验的店名，不能核验就留空","scene":"storefront|banner|street_sign|billboard|building|menu|coupon|unknown","confidence":0.0}
不确定就说看到了什么，不要猜品牌。黄色横幅、广告、路牌、键盘不是店。不要编排队和人均。"""

    const val ENVIRONMENT = """你在看用户眼前稳定下来的一帧。只输出一个 JSON，不要 markdown：
{"observation":{"sceneBrief":"2～4句中文，只写现在看着什么","change":"相对上一份环境的一句变化，没变就写没有明显变化","salientText":"画面上清晰可读的关键文字","objects":["物体"],"actions":["动作"],"actors":["画面里能看见的人，看不见就留空"],"location":"开放描述当前所在之处，不要用封闭枚举","observedClaims":[{"id":"c1","text":"画面直接证据","evidence":"scene"}]},"navigation":{"spaceType":"corridor|junction|elevator|stairs|entrance|storefront|signage|other","exits":[{"dir":"left|right|ahead","label":"出口或导视文字"}],"guideDir":"left|right|ahead","storeNames":["能看清的店招"],"blocked":false,"floorCandidate":"能从楼层/电梯/导览标识读到的层号，没有就留空","floorEvidence":"层号来自哪块标识，没有就留空"},"eventProposal":{"operation":"start|continue|transition|complete|revise|no_event","eventSummary":"当前活动的开放描述，没有把握就留空","hypothesis":"跨帧目的或因果，不是直接看见的就放这里","evidenceLevel":"observed|inferred|hypothesis","observedClaims":[{"id":"c1","text":"直接证据","evidence":"scene"}],"inferredClaims":[{"id":"i1","text":"根据连续证据做出的判断","evidence":"thread"}],"relations":[{"relatedEventId":"只能引用输入里已有的活动id","type":"continues|supports|contradicts|follows|returns_to|unrelated","evidenceRefs":["c1"]}],"actors":[],"objects":[],"actions":[],"location":""},"confidence":0.0}
画面直接证据进 observedClaims；目的、因果和跨帧总结进 inferredClaims 或 hypothesis。新证据可以 revise 旧推断。不要编造 episodeId、时间、位姿或拓扑节点，只能引用输入里已有的。地点、动作、对象用开放词汇，不要用固定活动名单。禁止猜店名、编排队、编人均、编层号。广告上的数字不是楼层。OCR 只是参考。没有导视不要编方向。看见可通行的门、出口、通道就填 spaceType=entrance，并在 exits 里写 dir（left|right|ahead）；没看见就留空。"""
}
