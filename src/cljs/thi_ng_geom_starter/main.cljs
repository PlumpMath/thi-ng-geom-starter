(ns thi-ng-geom-starter.main
  (:require [reagent.core :as reagent]
            [thi.ng.math.core :as m :refer [PI HALF_PI TWO_PI]]
            [thi.ng.geom.gl.core :as gl]
            [thi.ng.geom.gl.webgl.constants :as glc]
            [thi.ng.geom.gl.webgl.animator :as anim]
            [thi.ng.geom.gl.buffers :as buf]
            [thi.ng.geom.gl.fx :as fx]
            [thi.ng.geom.gl.shaders :as sh]
            [thi.ng.geom.gl.glmesh :as glm]
            [thi.ng.geom.gl.camera :as cam]
            [thi.ng.geom.gl.shaders :as sh]
            [thi.ng.geom.gl.shaders.lambert :as lambert]
            [thi.ng.geom.core :as g]
            [thi.ng.geom.vector :as v :refer [vec2 vec3]]
            [thi.ng.geom.matrix :as mat :refer [M44]]
            [thi.ng.geom.aabb :as a]
            [thi.ng.geom.attribs :as attr]
            [thi.ng.domus.core :as dom]
            [thi.ng.color.core :as col]
            [thi.ng.strf.core :as f]
            [thi-ng-geom-starter.shaders :as shaders])
    (:require-macros [cljs-log.core :refer [debug info warn severe]]))

(defonce app (reagent/atom {:stream {:state :wait}
                            :curr-shader :thresh}))

(defn set-stream-state! [state]
  (swap! app assoc-in [:stream :state] state))

(defn init-video-texture [video]
  (let [tex (buf/make-canvas-texture
             (:gl @app)
             video
             {:filter      glc/linear
              :wrap        glc/clamp-to-edge
              :width       (.-width video)
              :height      (.-height video)
              :flip        true
              :premultiply false})]
    (debug "SWAPPING!")
    (swap! app assoc-in [:scene :img :shader :state :tex] tex)))

(defn activate-rtc-stream [video stream]
  (swap! app assoc-in [:stream :video] video)
  (set! (.-onerror video)
        (fn [] (.stop stream) (set-stream-state! :error)))
  (set! (.-onended stream)
        (fn [] (.stop stream) (set-stream-state! :stopped)))
  (set! (.-src video)
        (.createObjectURL (or (aget js/window "URL") (aget js/window "webkitURL")) stream))
  (set-stream-state! :ready)
  (init-video-texture video))

(defn init-rtc-stream [w h]
  (let [video (dom/create-dom!
               [:video {:width w :height h :hidden true :autoplay true}]
               (.-body js/document))]
    (cond
      (aget js/navigator "webkitGetUserMedia")
      (.webkitGetUserMedia js/navigator #js {:video true}
                           #(activate-rtc-stream video %)
                           #(set-stream-state! :forbidden))

      (aget js/navigator "mozGetUserMedia")
      (.mozGetUserMedia js/navigator #js {:video true}
                        #(activate-rtc-stream video %)
                        #(set-stream-state! :forbidden))

      :else
      (set-stream-state! :unavailable))))

(def shader-spec
  {:vs "void main() {
    vUV = uv;
    gl_Position = proj * view * model * vec4(position, 1.0);
    }"
   :fs "void main() {
    gl_FragColor = texture2D(tex, vUV);
    }"
   :uniforms {:model    [:mat4 M44]
              :view     :mat4
              :proj     :mat4
              :tex      :sampler2D}
   :attribs  {:position :vec3
              :uv       :vec2}
   :varying  {:vUV      :vec2}
   :state    {:depth-test false
              :blend      true
              :blend-fn   [glc/src-alpha glc/one]}})

(defn make-model [gl]
  (-> (a/aabb 1)
      (g/center)
      (g/as-mesh
       {:mesh    (glm/indexed-gl-mesh 12 #{:uv})
        ;;:flags   :nsb
        :attribs {:uv (attr/face-attribs (attr/uv-cube-map-v 256 false))}})
      (gl/as-gl-buffer-spec {})
      (assoc :shader (sh/make-shader-from-spec gl shader-spec))
      (gl/make-buffers-in-spec gl glc/static-draw)))

(defn rebuild-viewport [app]
  (let [gl (:gl app)
        _  (gl/set-viewport gl {:p [0 0] :size [(.-innerWidth js/window) (.-innerHeight js/window)]})
        vr (gl/get-viewport-rect gl)]
    (assoc app
           :view-rect vr
           ;; :model (make-model gl vr)
           )))

(defn init-app [_]
  (debug "INIT")
  (let [gl        (gl/gl-context "main")
        view-rect (gl/get-viewport-rect gl)
        model     (make-model gl)
        tex-ready (volatile! false)
        file-tex  (buf/load-texture gl {:callback (fn [tex img] (vreset! tex-ready true))
                                        :src      "img/cubev.png"
                                        :flip     true})]
    (swap! app merge {:gl        gl
                      :view-rect view-rect
                      :model     model
                      :img       (-> (fx/init-fx-quad gl)
                                     #_(assoc :shader thresh))
                      :tex-ready tex-ready
                      :file-tex  file-tex})))

(defn update-app [this]
  (fn [t frame]
    (when (:active (reagent/state this))
      (let [{:keys [gl view-rect model stream tex-ready file-tex]} @app]
        (when @tex-ready
          (gl/bind file-tex)
          (doto gl
            (gl/set-viewport view-rect)
            (gl/clear-color-and-depth-buffer col/GRAY 1)
            (gl/draw-with-shader
             (-> model
                 (cam/apply
                  (cam/perspective-camera
                   {:eye (vec3 0 0 1.25)
                    ;;:up (m/normalize (vec3 (Math/sin t) 1 0))
                    :fov 90
                    :aspect view-rect}))
                 (assoc-in [:uniforms :model]
                           (-> M44 (g/rotate-x t) (g/rotate-y (* t 2)))))))))

      true)))
