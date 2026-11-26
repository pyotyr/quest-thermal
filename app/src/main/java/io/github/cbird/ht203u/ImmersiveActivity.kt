package io.github.cbird.ht203u

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.PanelShapeType
import com.meta.spatial.toolkit.ActivityPanelRegistration
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.vr.VRFeature
import kotlin.math.tan

/** Shared with the camera Activity embedded in this process. */
object ThermalViewMode {
    @Volatile var headLocked = true
}

/** Owns the VR scene; MainActivity continues to own USB capture and its controls. */
class ImmersiveActivity : AppSystemActivity() {
    @Volatile private var panelObject: PanelSceneObject? = null
    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ActivityPanelRegistration(
            R.id.thermal_panel,
            classIdCreator = { MainActivity::class.java },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 3.28f, height = 2.30f),
                    display = DpDisplayOptions(width = 880f, height = 720f, dpi = 160),
                    style = PanelStyleOptions(themeResourceId = R.style.ThermalPanelTheme),
                )
            },
            panelSetup = { sceneObject, _ -> panelObject = sceneObject },
        ),
        ActivityPanelRegistration(
            R.id.controls_panel,
            classIdCreator = { ControlsActivity::class.java },
            settingsCreator = { UIPanelSettings(shape = QuadShapeOptions(width=.9f,height=.7f),
                display=DpDisplayOptions(width=900f,height=700f,dpi=160),
                style=PanelStyleOptions(themeResourceId=R.style.ThermalPanelTheme)) },
        ),
    )

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.enablePassthrough(true)
        val panel = Entity.create(Panel(R.id.thermal_panel), Transform())
        val controls = Entity.create(Panel(R.id.controls_panel), Transform(), Visible(false))
        systemManager.registerSystem(object : SystemBase() {
            private var anchor: Pose? = null
            private var appliedGeometry: List<Float>? = null
            private var controlsShown = false
            override fun execute() {
                val settings = ViewerState.settings
                // Use the full viewer orientation: yaw, pitch and roll, with no
                // smoothing or yaw-only constraint. The panel stays in the same
                // place in the user's field of view while passthrough remains visible.
                if ((settings.headLocked && !ViewerState.calibrating) || anchor == null) anchor = scene.getViewerPose()
                val distance = settings.distance
                fun tangent(degrees: Float) = tan(Math.toRadians(degrees.toDouble())).toFloat()
                panel.setComponent(Transform(anchor!! * Pose(Vector3(0f,0f,distance))))
                if (ViewerState.optionsVisible != controlsShown) {
                    controlsShown = ViewerState.optionsVisible
                    if (controlsShown) controls.setComponent(Transform(scene.getViewerPose() * Pose(Vector3(0f,-.35f,.8f))))
                    controls.setComponent(Visible(controlsShown))
                }
                val geometry = listOf(distance,
                    if (settings.curved) 1f else 0f)
                val sceneObject = panelObject ?: return
                if (geometry != appliedGeometry) {
                    val config = sceneObject.panelShapeConfig ?: return
                    config.width = if (settings.curved)
                        distance * Math.toRadians(110.0).toFloat()
                    else 2f * distance * tangent(55f)
                    config.height = 2f * distance * tangent(45f)
                    config.panelShapeType = if (settings.curved) PanelShapeType.CYLINDER else PanelShapeType.QUAD
                    config.radiusForCylinderOrSphere = distance
                    config.enableTransparent = true
                    config.includeGlass = false
                    config.alphaMode = AlphaMode.PREMULTIPLIED
                    sceneObject.reshape(config)
                    appliedGeometry = geometry
                }
            }
        })
    }
}
