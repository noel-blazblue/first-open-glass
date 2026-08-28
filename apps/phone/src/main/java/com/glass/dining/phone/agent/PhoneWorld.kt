package com.glass.dining.phone.agent

import android.app.Application
import com.glass.dining.phone.PhoneUiState
import com.glass.dining.phone.nav.GeoPoint
import com.glass.dining.shared.agent.SceneObservation
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.model.ActiveSkill
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.shared.vision.VisionIntent
import kotlinx.coroutines.flow.MutableStateFlow

interface PhoneWorld {
    val session: DiningSession
    val app: Application
    val ui: MutableStateFlow<PhoneUiState>
    val question: String
    val spokenFloor: String
    val currentFloor: String
    var recommendedThisTurn: Boolean
    var selectedThisTurn: Boolean
    val observation: SceneObservation?
    fun gps(): GeoPoint?
    fun awaitGps(timeoutMs: Long): GeoPoint?
    fun hasGpsPermission(): Boolean
    fun amapKey(): String
    fun reloadCatalogWithGps()
    fun capture(intent: VisionIntent, spatial: Boolean = false, bindStore: Boolean = true): String
    fun startNav(spoken: String): String?
    fun stopNav(silent: Boolean)
    fun publishSkillCard(card: HudCard, skill: ActiveSkill, status: String, intent: String)
    fun publishMatch(result: MatchResult, source: String)
    fun publishStore(place: PlaceProfile, status: String, intent: String, caption: String = "")
    fun publishTalk(speak: String, tts: Boolean)
    fun showCard(card: HudCard)
    fun rememberSpokenStore()
    fun rememberVisionStore(id: String)
    fun bindPlace(place: PlaceProfile): MatchResult
    fun latestPlaces(): List<PlaceProfile>
    fun rememberPlaces(places: List<PlaceProfile>)
}
