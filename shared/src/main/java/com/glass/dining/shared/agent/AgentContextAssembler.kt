package com.glass.dining.shared.agent

import com.glass.dining.shared.place.PlaceFacts

object AgentContextAssembler {
    fun format(world: WorldContext): String {
        val env = world.environment
        val lines = buildList {
            add(taskLine(world))
            add(locationLine(world))
            add(viewLine(world, env))
            recentLine(env)?.let(::add)
            activityLine(env)?.let(::add)
            eventsLine(env)?.let(::add)
            boundLine(world)?.let(::add)
            if (world.disambiguation.isNotEmpty()) {
                add("【待选择地点】" + world.disambiguation.joinToString("、") { it.name })
            }
            if (world.recentSearch.isNotEmpty()) {
                add("【最近搜索】" + world.recentSearch.take(4).joinToString("、") { PlaceFacts.listLabel(it) } + "（附近搜索缓存，不等于待选择）")
            }
            add(catalogLine(world))
            add(pendingLine(world))
        }
        return lines.joinToString("\n")
    }

    fun debug(world: WorldContext): String {
        val place = world.boundPlace
        val obs = world.observation
        return buildString {
            append("nav=").append(world.navActive)
            append(" role=").append(world.businessRole)
            append(" bound=").append(place?.name ?: "无")
            if (place != null) append("(").append(place.source).append(")")
            append(" catalog=").append(world.catalogCount)
            append(" gpsPerm=").append(world.gpsPermission)
            append(" gpsFix=").append(world.hasGpsFix)
            if (obs != null && obs.scene != SceneObservation.SCENE_UNKNOWN) {
                append("\nobs scene=").append(obs.scene)
                append(" text=").append(obs.visibleText.take(24))
                append(" conf=").append("%.2f".format(obs.confidence))
                append(" candidate=").append(obs.storeCandidate.ifBlank { "无" })
            }
        }
    }

    private fun taskLine(world: WorldContext): String {
        val dest = world.boundPlace?.name
        return when {
            world.navActive && dest != null -> "【当前任务】正在步行导航；目的地=$dest"
            world.navActive -> "【当前任务】正在步行导航；目的地未确定"
            world.pendingPay.isNotBlank() -> "【待处理】${world.pendingPay}"
            world.pendingCoupon.isNotBlank() -> "【待处理】核销=${world.pendingCoupon}"
            else -> "【当前任务】响应用户当前请求"
        }
    }

    private fun locationLine(world: WorldContext): String {
        val env = world.environment
        val floor = env?.let { FloorQueryPolicy.resolve(it) }
        val floorPart = if (floor == null) {
            "楼层=未知"
        } else {
            "楼层=${floor.floor}（来源：${floorSource(floor.source)}）"
        }
        val perm = if (world.gpsPermission) "已授权" else "未授权"
        val fix = if (world.hasGpsFix) "已获取" else "未获取"
        return "【用户所在】语义地点=未知；定位权限=$perm；GPS坐标=$fix；$floorPart"
    }

    private fun viewLine(world: WorldContext, env: EnvironmentState?): String {
        val brief = env?.currentBrief.orEmpty()
        if (brief.isNotBlank()) return "【当前视野】$brief"
        val obs = world.observation
        if (obs != null && obs.scene != SceneObservation.SCENE_UNKNOWN && obs.stability >= 1) {
            val seen = obs.visibleText.take(24).ifBlank { obs.label }
            val extra = if (obs.storeCandidate.isNotBlank()) " 疑似门头=${obs.storeCandidate}（不是已确认门店）" else ""
            return "【当前视野】瞬时画面：${seen.ifBlank { obs.scene }}$extra"
        }
        return "【当前视野】无稳定环境记录"
    }

    private fun recentLine(env: EnvironmentState?): String? {
        val obs = env?.recentObservations
            ?.filter { it.status != EnvironmentObservation.STATUS_STALE }
            ?.take(4)
            .orEmpty()
        if (obs.isEmpty()) return null
        val body = obs.joinToString("；") { item ->
            val tag = if (item.kind == EnvironmentObservation.KIND_FLOOR) {
                "楼层标识"
            } else if (item.kind == EnvironmentObservation.KIND_TEXT) {
                "可见文字"
            } else {
                item.kind
            }
            val mark = if (item.status == EnvironmentObservation.STATUS_CONFIRMED) "视觉确认" else "曾看到"
            "$tag=${item.value}（$mark）"
        }
        return "【近期观察】$body"
    }

    private fun activityLine(env: EnvironmentState?): String? {
        val event = env?.activeEvent ?: return null
        val body = event.summary.ifBlank { event.hypothesis }
        if (body.isBlank()) return null
        return "【当前活动】$body（${EnvironmentObservation.evidenceMark(event.confidence)}）"
    }

    private fun eventsLine(env: EnvironmentState?): String? {
        val events = env?.recentEvents.orEmpty()
        if (events.isEmpty()) return null
        return "【近期事件】" + events.take(3).joinToString("；") { it.summary }
    }

    private fun boundLine(world: WorldContext): String? {
        val place = world.boundPlace ?: return null
        val role = when (world.businessRole) {
            WorldContext.ROLE_DESTINATION -> "导航目的地；不是用户当前位置"
            WorldContext.ROLE_APPROACHING -> "导航目的地；已接近，仍不是已确认到店事实"
            WorldContext.ROLE_SERVING -> "当前服务门店"
            else -> "当前查看门店；不是用户当前位置"
        }
        val facts = world.boundProfile?.let { PlaceFacts.contextFacts(it) }.orEmpty()
        val head = "【业务对象】${place.name}（角色：$role）"
        return if (facts.isBlank()) head else "$head\n【门店资料】$facts"
    }

    private fun catalogLine(world: WorldContext): String {
        return if (world.catalogCount > 0) {
            "【本地目录】餐饮增强数据 ${world.catalogCount} 家，可 recommend"
        } else {
            "【本地目录】0 家，不要 recommend，搜附近公开地点"
        }
    }

    private fun pendingLine(world: WorldContext): String {
        return "【待处理】支付=${world.pendingPay.ifBlank { "无" }}；核销=${world.pendingCoupon.ifBlank { "无" }}"
    }

    private fun floorSource(source: String): String {
        return when (source) {
            "user" -> "用户确认"
            "sign" -> "视觉确认"
            else -> source
        }
    }
}
