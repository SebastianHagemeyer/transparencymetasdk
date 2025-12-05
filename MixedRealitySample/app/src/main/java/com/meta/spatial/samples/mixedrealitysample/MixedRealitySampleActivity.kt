/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.mixedrealitysample

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.mruk.AnchorProceduralMesh
import com.meta.spatial.mruk.AnchorProceduralMeshConfig
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.mruk.MRUKRoom
import com.meta.spatial.mruk.MRUKSceneEventListener
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.physics.PhysicsFeature
import com.meta.spatial.physics.PhysicsWorldBounds
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.SceneObjectSystem
import java.util.concurrent.CompletableFuture
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MixedRealitySampleActivity : AppSystemActivity() {

  var glxfLoaded = false
  private val activityScope = CoroutineScope(Dispatchers.Main)
  private var gltfxEntity: Entity? = null
  private var ballShooter: BallShooter? = null
  private var gotAllAnchors = false
  private var debug = false
  private lateinit var mrukFeature: MRUKFeature
  private lateinit var sceneEventListener: MRUKSceneEventListener
  private lateinit var procMeshSpawner: AnchorProceduralMesh

  // Edge geometry for room bounds (walls, floors, ceiling)
  private val roomEdgeEntities = mutableListOf<Entity>()
  private lateinit var edgeBoxMaterial: SceneMaterial

  override fun registerFeatures(): List<SpatialFeature> {
    mrukFeature = MRUKFeature(this, systemManager)
    val features =
        mutableListOf(
            PhysicsFeature(spatial, worldBounds = PhysicsWorldBounds(minY = -100.0f)),
            VRFeature(this),
            ComposeFeature(),
            mrukFeature,
        )
    if (BuildConfig.DEBUG) {
      features.add(CastInputForwardFeature(this))
      features.add(HotReloadFeature(this))
      features.add(OVRMetricsFeature(this, OVRMetricsDataModel() { numberOfMeshes() }))
      features.add(DataModelInspectorFeature(spatial, this.componentManager))
    }
    return features
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    systemManager.registerSystem(UiPanelUpdateSystem())

    // Create simple translucent green material for edge box geometry (room bounds)
    // Uses solidColor shader - simple unlit color with alpha blending
    edgeBoxMaterial = SceneMaterial.custom(
        "solidColor",
        arrayOf(
            SceneMaterialAttribute("customColor", SceneMaterialDataType.Vector4)
        )
    ).apply {
        setBlendMode(BlendMode.TRANSLUCENT)
        setAttribute("customColor", Vector4(0f, 1f, 0f, 0.35f)) // green with 35% alpha
    }

    // Create edge-only shader material for furniture (box-like objects)
    val furnitureEdgeMaterial = SceneMaterial.custom(
        "edgeOnly",
        arrayOf(
            SceneMaterialAttribute("customColor", SceneMaterialDataType.Vector4),
            SceneMaterialAttribute("edgeParams", SceneMaterialDataType.Vector4)
        )
    ).apply {
        setBlendMode(BlendMode.TRANSLUCENT)
        setAttribute("customColor", Vector4(0f, 1f, 0f, 0.3f)) // green with 30% alpha
        setAttribute("edgeParams", Vector4(0.02f, 0f, 0f, 0f)) // thickness = 2cm
    }

    // Apply edge shader ONLY to furniture - NOT to room bounds (FLOOR, WALL_FACE, CEILING)
    // Room bounds will use geometry-based edge boxes instead
    procMeshSpawner =
        AnchorProceduralMesh(
            mrukFeature,
            mapOf(
                // Furniture uses edge shader (works well for box-like objects with good UVs)
                MRUKLabel.TABLE to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.COUCH to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.WINDOW_FRAME to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.DOOR_FRAME to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.STORAGE to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.BED to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.SCREEN to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.LAMP to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.PLANT to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                MRUKLabel.OTHER to AnchorProceduralMeshConfig(furnitureEdgeMaterial, true),
                // Note: FLOOR, WALL_FACE, CEILING are NOT included here
                // They will be handled by createRoomBoundsEdges() with geometry boxes
            ),
        )

    // Enable MR mode
    systemManager.findSystem<LocomotionSystem>().enableLocomotion(false)
    scene.enablePassthrough(true)

    loadGLXF { composition ->
      systemManager.unregisterSystem<BallShooter>()
      glxfLoaded = true
      val bball = composition.getNodeByName("BasketBall").entity
      val mesh = bball.getComponent<Mesh>()
      ballShooter = BallShooter(mesh)
      systemManager.registerSystem(ballShooter!!)

      sceneEventListener =
          object : MRUKSceneEventListener {
            override fun onRoomAdded(room: MRUKRoom) {
              // If a room exists, it has a floor. Remove the default floor.
              val floor = composition.tryGetNodeByName("defaultFloor")
              floor!!.entity.destroy()
            }

            override fun onRoomRemoved(room: MRUKRoom) {
              // Clean up edge entities when room is removed
              clearRoomBoundsEdges()
            }

            override fun onAnchorAdded(room: MRUKRoom, anchor: Entity) {
              // Create edge geometry for room bounds anchors (walls, floor, ceiling)
              onAnchorAddedHandler(room, anchor)
            }
          }
      mrukFeature.addSceneEventListener(sceneEventListener)

      if (checkSelfPermission(PERMISSION_USE_SCENE) != PackageManager.PERMISSION_GRANTED) {
        log("Scene permission has not been granted, requesting $PERMISSION_USE_SCENE")
        requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_CODE_PERMISSION_USE_SCENE)
      } else {
        log("Scene permission has already been granted!")
        loadSceneFromDevice()
      }
    }
  }

  private fun loadSceneFromDevice() {
    log("Loading scene from device...")
    mrukFeature.loadSceneFromDevice().whenComplete { result: MRUKLoadDeviceResult, _ ->
      if (result != MRUKLoadDeviceResult.SUCCESS) {
        log("Error loading scene from device: $result")
      } else {
        log("Scene loaded from device")
      }
    }
  }

  override fun onSpatialShutdown() {
    clearRoomBoundsEdges()
    procMeshSpawner.destroy()
    mrukFeature.removeSceneEventListener(sceneEventListener)
    super.onSpatialShutdown()
  }

  override fun onSceneReady() {
    super.onSceneReady()

    scene.setLightingEnvironment(
        ambientColor = Vector3(0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )
    scene.updateIBLEnvironment("environment.env")

    // Create a cube with custom shader material (transparent red)
    val cubeEntity = Entity.create(
        listOf(
            Transform(Pose(Vector3(0f, 1f, -2f), Quaternion(0f, 0f, 0f, 1f)))
        )
    )

    // Create custom material with custom attributes (following MediaPlayerSample pattern)
    val solidColorMaterial = SceneMaterial.custom(
        "solidColor",
        arrayOf(
            SceneMaterialAttribute("customColor", SceneMaterialDataType.Vector4)
        )
    ).apply {
        setBlendMode(BlendMode.TRANSLUCENT)
        setAttribute("customColor", Vector4(1f, 0f, 0f, 0.5f)) // RGBA: red with 50% alpha
    }

    val cubeMesh = SceneMesh.box(Vector3(-0.25f, -0.25f, -0.25f), Vector3(0.25f, 0.25f, 0.25f), solidColorMaterial)
    val sceneObject = SceneObject(scene, cubeMesh, "redCube", cubeEntity)
    systemManager.findSystem<SceneObjectSystem>().addSceneObject(
        cubeEntity,
        CompletableFuture<SceneObject>().apply { complete(sceneObject) }
    )
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray,
  ) {
    if (
        requestCode == REQUEST_CODE_PERMISSION_USE_SCENE &&
            permissions.size == 1 &&
            permissions[0] == PERMISSION_USE_SCENE
    ) {
      val granted = grantResults[0] == PackageManager.PERMISSION_GRANTED
      if (granted) {
        log("Use scene permission has been granted")
        loadSceneFromDevice()
      } else {
        log("Use scene permission was DENIED!")
      }
    }
  }

  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        ComposeViewPanelRegistration(
            R.id.panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  AboutPanelLayout(
                      onConfigureRoomClick = {
                        scene.requestSceneCapture().whenComplete { _, _ -> loadSceneFromDevice() }
                      },
                      onToggleDebugClick = {
                        debug = !debug
                        spatial.enablePhysicsDebugLines(debug)
                      },
                  )
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = ABOUT_PANEL_WIDTH, height = ABOUT_PANEL_HEIGHT),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(),
              )
            },
        )
    )
  }

  private fun loadGLXF(onLoaded: ((GLXFInfo) -> Unit) = {}): Job {
    gltfxEntity = Entity.create()
    return activityScope.launch {
      glXFManager.inflateGLXF(
          "apk:///scenes/Composition.glxf".toUri(),
          rootEntity = gltfxEntity!!,
          keyName = GLXF_SCENE,
          onLoaded = onLoaded,
      )
    }
  }

  // --- Room bounds edge geometry functions ---

  // Labels that represent room bounds (walls, floor, ceiling)
  private val roomBoundsLabels = setOf(MRUKLabel.WALL_FACE, MRUKLabel.FLOOR, MRUKLabel.CEILING)

  private fun clearRoomBoundsEdges() {
    for (entity in roomEdgeEntities) {
      entity.destroy()
    }
    roomEdgeEntities.clear()
  }

  /**
   * Called when an anchor is added to a room. Creates edge geometry for room bounds anchors.
   */
  private fun onAnchorAddedHandler(room: MRUKRoom, anchorEntity: Entity) {
    // Get the MRUKAnchor component to check its labels
    val anchorComponent = anchorEntity.getComponent<com.meta.spatial.mruk.MRUKAnchor>()

    // Check if this anchor has any room bounds labels (wall, floor, or ceiling)
    val anchorLabels = mutableListOf<String>()
    for (i in 0 until anchorComponent.labelsCount) {
      anchorComponent.labels[i]?.let { anchorLabels.add(it) }
    }

    val hasRoomBoundsLabel = anchorLabels.any { labelName ->
      roomBoundsLabels.any { it.name == labelName }
    }

    if (!hasRoomBoundsLabel) {
      return
    }

    // Get the MRUKPlane component for plane bounds (walls, floors, ceilings have this)
    val planeComponent = anchorEntity.tryGetComponent<com.meta.spatial.mruk.MRUKPlane>()
    if (planeComponent == null) {
      log("Anchor has no MRUKPlane component, skipping: $anchorLabels")
      return
    }

    // Calculate width and height from plane min/max
    val width = planeComponent.max.x - planeComponent.min.x
    val height = planeComponent.max.y - planeComponent.min.y

    // Get the anchor's transform/pose
    val transform = anchorEntity.getComponent<Transform>()
    val anchorPose = transform.transform

    log("Creating edges for anchor: labels=$anchorLabels, size=${width}x${height}")

    // Create the 4 edge boxes for this plane
    val edges = createPlaneOutlineEdges(
        centerPose = anchorPose,
        width = width,
        height = height,
        thickness = EDGE_THICKNESS
    )
    roomEdgeEntities.addAll(edges)
  }

  /**
   * Creates 4 edge box entities outlining a rectangular plane.
   *
   * The plane is defined by:
   * - centerPose: position and orientation of the plane center
   * - width: horizontal extent (along local X axis)
   * - height: vertical extent (along local Y axis)
   * - thickness: how thick the edge boxes should be
   *
   * The plane's local coordinate system:
   * - X axis: horizontal (width direction)
   * - Y axis: vertical (height direction)
   * - Z axis: normal to the plane (points outward)
   */
  private fun createPlaneOutlineEdges(
      centerPose: Pose,
      width: Float,
      height: Float,
      thickness: Float
  ): List<Entity> {
    val entities = mutableListOf<Entity>()
    val halfWidth = width / 2f
    val halfHeight = height / 2f
    val halfThick = thickness / 2f

    // We need to create 4 edges: top, bottom, left, right
    // Each edge is positioned relative to the plane center using the plane's orientation

    // Edge definitions: (localOffset, boxSize)
    // - Top edge: at +Y, spans full width
    // - Bottom edge: at -Y, spans full width
    // - Left edge: at -X, spans full height (minus corners to avoid overlap)
    // - Right edge: at +X, spans full height (minus corners to avoid overlap)

    data class EdgeDef(
        val localOffset: Vector3,
        val boxHalfSize: Vector3
    )

    val edgeDefs = listOf(
        // Top edge: horizontal bar at top
        EdgeDef(
            localOffset = Vector3(0f, halfHeight, 0f),
            boxHalfSize = Vector3(halfWidth, halfThick, halfThick)
        ),
        // Bottom edge: horizontal bar at bottom
        EdgeDef(
            localOffset = Vector3(0f, -halfHeight, 0f),
            boxHalfSize = Vector3(halfWidth, halfThick, halfThick)
        ),
        // Left edge: vertical bar at left (shortened to fit between top/bottom)
        EdgeDef(
            localOffset = Vector3(-halfWidth, 0f, 0f),
            boxHalfSize = Vector3(halfThick, halfHeight - thickness, halfThick)
        ),
        // Right edge: vertical bar at right (shortened to fit between top/bottom)
        EdgeDef(
            localOffset = Vector3(halfWidth, 0f, 0f),
            boxHalfSize = Vector3(halfThick, halfHeight - thickness, halfThick)
        )
    )

    for ((index, edgeDef) in edgeDefs.withIndex()) {
      // Transform local offset to world position using the plane's pose
      val worldOffset = centerPose.q.times(edgeDef.localOffset)
      val worldPos = centerPose.t + worldOffset

      // Create edge entity with transform only (SceneObject handles the mesh)
      val edgePose = Pose(worldPos, centerPose.q)

      val entity = Entity.create(
          listOf(
              Transform(edgePose)
          )
      )

      // Create scene object with box mesh and material
      val min = -edgeDef.boxHalfSize
      val max = edgeDef.boxHalfSize
      val boxMesh = SceneMesh.box(
          Vector3(min.x, min.y, min.z),
          Vector3(max.x, max.y, max.z),
          edgeBoxMaterial
      )
      val sceneObject = SceneObject(scene, boxMesh, "roomEdge_${index}", entity)
      systemManager.findSystem<SceneObjectSystem>().addSceneObject(
          entity,
          CompletableFuture<SceneObject>().apply { complete(sceneObject) }
      )

      entities.add(entity)
    }

    return entities
  }

  companion object {
    const val EDGE_THICKNESS = 0.02f // 2cm edge thickness
    const val TAG = "MixedRealitySampleActivityDebug"
    const val PERMISSION_USE_SCENE: String = "com.oculus.permission.USE_SCENE"
    const val REQUEST_CODE_PERMISSION_USE_SCENE: Int = 1
    const val GLXF_SCENE = "GLXF_SCENE"
  }
}

fun log(msg: String) {
  Log.d(MixedRealitySampleActivity.TAG, msg)
}
