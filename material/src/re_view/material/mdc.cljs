(ns re-view.material.mdc
  (:require [re-view.core :as v]
            [re-view.view-spec :as s]
            [goog.dom :as gdom]
            [goog.dom.classes :as classes]
            [goog.dom.dataset :as dataset]
            [goog.object :as gobj]
            [goog.functions :as gfn]
            [re-view.material.util :as util]
            ["@material/dialog/util" :refer [createFocusTrapInstance]]
            ["@material/animation" :refer [getCorrectEventName]]
            ["@material/drawer/util" :as mdc-util]


    ;; temporary - will move to own namespaces
            ["@material/base" :as base]
            ["@material/dialog" :as dialog]
            ["@material/drawer" :as drawer]
            ["@material/form-field" :as form-field]
            ["@material/grid-list" :as grid-list]
            ["@material/icon-toggle" :as icon-toggle]
            ["@material/menu" :as menu]
            ["@material/radio" :as radio]
            ["@material/select" :as select]
            ["@material/snackbar" :as snackbar]
            ["@material/textfield" :as textfield]
            ["@material/toolbar" :as toolbar]

            ["react" :as react]
            [clojure.string :as string])
  (:require-macros [re-view.material.mdc :refer [defadapter]]))

(s/defspecs {::color         {:spec #{:primary :accent}
                              :doc  "Specifies color variable from theme."}
             ::raised        {:spec :Boolean
                              :doc  "Raised buttons gain elevation, and color is applied to background instead of text."}
             ::ripple        {:spec    :Boolean
                              :doc     "Enables ripple effect on click/tap"
                              :default true}
             ::compact       {:spec :Boolean
                              :doc  "Reduces horizontal padding"}
             ::auto-focus    {:spec :Boolean
                              :doc  "If true, focuses element on mount"}


             ::id            :String
             ::dirty         {:spec :Boolean
                              :doc  "If true, field should display validation errors"}
             ::dense         {:spec :Boolean
                              :doc  "Reduces text size and vertical padding"}
             ::disabled      {:spec         :Boolean
                              :doc          "Disables input element or button"
                              :pass-through true}
             ::label         {:spec :Element
                              :doc  "Label for input element or button"}
             ::on-change     :Function
             ::rtl           {:spec :Boolean
                              :doc  "Show content in right to left."}
             ::value         {:spec :String
                              :doc  "Providing a value causes an input component to be 'controlled'"}
             ::default-value {:spec :String
                              :doc  "For an uncontrolled component, sets the initial value"}})

(set! *warn-on-infer* true)
(def browser? (exists? js/window))
(def Document (when browser? js/document))
(def ^js/HTMLElement Body (when Document (.-body Document)))
(def Window (when browser? js/window))
(def conj-set (fnil conj #{}))
(def disj-set (fnil disj #{}))

(defn init
  "Instantiate mdc foundations for a re-view component
   (should be called in componentDidMount)."
  [component & adapters]
  (doseq [{:keys [name adapter]} adapters]
    (let [foundation (adapter component)]
      (gobj/set component (str "mdc" name) foundation)
      (.init foundation))))

(defn destroy
  "Destroy mdc foundation instances for component (should be called in componentWillUnmount)."
  [component & adapters]
  (doseq [{:keys [name]} adapters]
    (let [foundation (gobj/get component (str "mdc" name))]
      (when-let [onDestroy (aget foundation "adapter_" "onDestroy")]
        (onDestroy))
      (.destroy foundation))))

(defn current-target? [^js/Event e]
  (= (.-target e) (.-currentTarget e)))

(defn wrap-log [msg f]
  (fn [& args]
    (prn msg)
    (apply f args)))

(defn interaction-handler
  ([kind element event-type]
   (fn [handler]
     (this-as this
       (let [^js/HTMLElement target (cond->> element
                                             (string? element)
                                             (gobj/get this))
             event (mdc-util/remapEvent event-type)]
         (condp = kind
           :listen (.addEventListener target event handler (mdc-util/applyPassive))
           :unlisten (.removeEventListener target event handler))))))
  ([kind element]
   (fn [event-type handler]
     (this-as this
       (let [^js/Element target (cond->> element
                                         (string? element)
                                         (gobj/get this))
             event-type (mdc-util/remapEvent event-type)]
         (condp = kind
           :listen (.addEventListener target event-type handler (mdc-util/applyPassive))
           :unlisten (.removeEventListener target event-type handler)))))))

(defn adapter [component mdc-key]
  (gobj/getValueByKeys component (str "mdc" (name mdc-key)) "adapter_"))

(defn element [adapter k]
  (gobj/get adapter (name k)))

(defn styles-key [element-key]
  (keyword "mdc" (str (some-> element-key (name) (str "-")) "styles")))

(defn classes-key [element-key]
  (keyword "mdc" (str (some-> element-key (name) (str "-")) "classes")))

(defn style-handler
  ([mdc-key]
   (style-handler mdc-key :root))
  ([mdc-key element-key]
   (fn [attr val]
     (this-as adapter
       (util/add-styles (element adapter element-key) {attr val})
       (v/swap-silently! (aget adapter "state") assoc-in [(styles-key element-key) attr] val)))))

(defn mdc-style-update
  ([mdc-key]
   (mdc-style-update mdc-key "root"))
  ([mdc-key element-key]
   (fn [{:keys [view/state
                view/prev-state] :as this}]
     (let [target (element (adapter this mdc-key) element-key)
           style-key (styles-key element-key)]
       (util/add-styles target (get @state style-key) (get prev-state style-key))))))

#_(defn mdc-classes-update
    ([mdc-key]
     (mdc-classes-update mdc-key "root"))
    ([mdc-key element-key]
     (fn [{:keys [view/state] :as this}]
       (when-let [mdc-classes (seq (get @state (classes-key mdc-key)))]
         (let [target (element (adapter this mdc-key) element-key)]
           (doseq [class mdc-classes]
             (classes/add target class)))))))

(defn class-handler
  ([action] (class-handler action nil))
  ([action prefix]
   (let [f (condp = action :add conj-set :remove disj-set)]
     (fn [class-name]
       (this-as this
         (swap! (aget this "state") update (keyword "mdc" (str (aget this "name") (some-> prefix (str "-")) "-classes")) f class-name))))))

(def adapter-base
  {:addClass                         (class-handler :add)
   :removeClass                      (class-handler :remove)
   :hasClass                         #(this-as this
                                        (classes/has (gobj/get this "root") %))
   :registerInteractionHandler       (interaction-handler :listen "root")
   :deregisterInteractionHandler     (interaction-handler :unlisten "root")
   :registerDocumentKeydownHandler   (interaction-handler :listen Document "keydown")
   :deregisterDocumentKeydownHandler (interaction-handler :unlisten Document "keydown")
   :registerDocumentClickHandler     (interaction-handler :listen Document "click")
   :deregisterDocumentClickHandler   (interaction-handler :unlisten Document "click")
   :isRtl                            #(this-as this
                                        (let [^js/Element root (gobj/get this "root")
                                              ^js/CSSStyleDeclaration styles (js/getComputedStyle root)]
                                          (= "rtl" (.getPropertyValue styles "direction"))))
   :addBodyClass                     #(classes/add (gobj/get Document "body") %)
   :removeBodyClass                  #(classes/remove (gobj/get Document "body") %)})

(defn bind-adapter
  "Return methods that bind an adapter to a specific component instance"
  [{:keys [view/state] :as this}]
  (let [root-node (v/dom-node this)]
    {:root        root-node
     :nativeInput (util/find-tag root-node #"INPUT|TEXTAREA")
     :state       (get this :view/state)
     :component   this}))

(defn make-foundation
  "Extends adapter with base adapter methods, and wraps with Foundation class."
  [name foundation-class methods]
  (fn [this]
    (foundation-class. (->> (merge (bind-adapter this)
                                   adapter-base
                                   (if (fn? methods) (methods this) methods)
                                   {:name name})
                            (clj->js)))))

(defadapter Textfield
  textfield/MDCTextfieldFoundation
  []
  {:addClassToLabel               (class-handler :add "label")
   :removeClassFromLabel          (class-handler :remove "label")
   :addClassToHelptext            (class-handler :add "help")
   :removeClassFromHelptext       (class-handler :remove "help")
   :helptextHasClass              #(this-as this (contains? (:mdc/help-classes @(gobj/get this "state")) %))
   :registerInputFocusHandler     (interaction-handler :listen "nativeInput" "focus")
   :deregisterInputFocusHandler   (interaction-handler :unlisten "nativeInput" "focus")
   :registerInputBlurHandler      (interaction-handler :listen "nativeInput" "blur")
   :deregisterInputBlurHandler    (interaction-handler :unlisten "nativeInput" "blur")
   :registerInputInputHandler     (interaction-handler :listen "nativeInput" "input")
   :deregisterInputInputHandler   (interaction-handler :unlisten "nativeInput" "input")
   :registerInputKeydownHandler   (interaction-handler :listen "nativeInput" "keydown")
   :deregisterInputKeydownHandler (interaction-handler :unlisten "nativeInput" "keydown")
   :setHelptextAttr               #(this-as this (swap! (gobj/get this "state") update :mdc/help-attrs assoc (keyword %1) %2))
   :removeHelptextAttr            #(this-as this (swap! (gobj/get this "state") update :mdc/help-attrs dissoc %1))
   :getNativeInput                #(this-as this (gobj/get this "nativeInput"))})


(defadapter Dialog
  dialog/MDCDialogFoundation
  [{:keys [view/state on-accept on-cancel] :as ^react/Component component}]
  (let [root (v/dom-node component)
        accept-btn (gdom/findNode root #(classes/has % "mdc-dialog__footer__button--accept"))
        surface (gdom/findNode root #(classes/has % "mdc-dialog__surface"))
        ^js/focusTrap focus-trap (createFocusTrapInstance surface accept-btn)]
    {:surface                             surface
     :acceptButton                        accept-btn
     :setStyle                            (style-handler :Dialog)
     :eventTargetHasClass                 (fn [target class-name]
                                            (util/closest target #(classes/has % class-name)))
     :registerSurfaceInteractionHandler   (interaction-handler :listen "surface")
     :deregisterSurfaceInteractionHandler (interaction-handler :unlisten "surface")
     :registerDocumentKeydownHandler      (interaction-handler :listen Document "keydown")
     :deregisterDocumentKeydownHandler    (interaction-handler :unlisten Document "keydown")
     :notifyAccept                        (or on-accept #(println :accept))
     :notifyCancel                        (or on-cancel #(println :cancel))
     :trapFocusOnSurface                  #(.activate focus-trap)
     :untrapFocusOnSurface                #(.deactivate focus-trap)
     :registerTransitionEndHandler        (interaction-handler :listen surface "transitionend")
     :deregisterTransitionEndHandler      (interaction-handler :unlisten surface "transitionend")
     :isDialog                            #(= % surface)
     }))


(defadapter TemporaryDrawer
  drawer/MDCTemporaryDrawerFoundation
  [{:keys [view/state] :as ^react/Component component}]
  (let [root (v/dom-node component)
        ^js/Element drawer (util/find-node root (fn [el] (classes/has el "mdc-temporary-drawer__drawer")))
        ]
    (cond-> {:drawer                             drawer
             :hasNecessaryDom                    #(do drawer)
             ;:registerInteractionHandler         #(.log js/console "i" %1 %2)
             ;:deregisterInteractionHandler       #(.log js/console %1 %2)
             :registerDrawerInteractionHandler   (interaction-handler :listen "drawer")
             :deregisterDrawerInteractionHandler (interaction-handler :unlisten "drawer")
             :registerTransitionEndHandler       (interaction-handler :listen "drawer" (getCorrectEventName Window "transitionend"))
             :deregisterTransitionEndHandler     (interaction-handler :unlisten "drawer" (getCorrectEventName Window "transitionend"))
             :getDrawerWidth                     #(util/force-layout drawer)
             :setTranslateX                      (fn [n]
                                                   (swap! state assoc-in [:mdc/root-styles (mdc-util/getTransformPropertyName)]
                                                          (when n (str "translateX(" n "px)"))))
             :updateCssVariable                  (fn [value]
                                                   (when (mdc-util/supportsCssCustomProperties)
                                                     (swap! state assoc-in [:mdc/root-styles (gobj/getValueByKeys drawer/MDCTemporaryDrawerFoundation "strings" "OPACITY_VAR_NAME")] value)))
             :getFocusableElements               #(.querySelectorAll drawer (gobj/getValueByKeys drawer/MDCTemporaryDrawerFoundation "strings" "FOCUSABLE_ELEMENTS"))
             :saveElementTabState                mdc-util/saveElementTabState
             :restoreElementTabState             mdc-util/restoreElementTabState
             :makeElementUntabbable              #(.setAttribute ^js/Element % "tabindex" -1)
             :isDrawer                           #(= % drawer)}
            (.-notifyOpen component) (assoc :notifyOpen (.-notifyOpen component))
            (.-notifyClose component) (assoc :notifyClose (.-notifyClose component)))))

(defadapter FormField
  form-field/MDCFormFieldFoundation
  [component]
  {:root                  (util/find-tag (v/dom-node component) #"LABEL")

   :activateInputRipple   #(this-as this (let [ripple (-> (gobj/get this "component")
                                                          (gobj/get "mdcRipple"))]
                                           (.activate ripple)))
   :deactivateInputRipple #(this-as this (let [ripple (-> (gobj/get this "component")
                                                          (gobj/get "mdcRipple"))]
                                           (.deactivate ripple)))})

(defn index-of
  "Index of x in js-coll, where js-coll is an array-like object that does not implement .indexOf (eg. HTMLCollection)"
  [^js/Array js-coll x]
  (let [length (.-length js-coll)]
    (loop [i 0]
      (cond (= i length) -1
            (= x (aget js-coll i)) i
            :else (recur (inc i))))))

(defadapter SimpleMenu
  menu/MDCSimpleMenuFoundation
  [{:keys [view/state] :as component}]
  (let [^js/HTMLElement root (v/dom-node component)
        get-container #(util/find-node root (fn [el] (classes/has el "mdc-simple-menu__items")))
        ^js/Element menuItemContainer (get-container)]
    {:menuItemContainer                menuItemContainer
     :hasNecessaryDom                  #(do true)
     :getInnerDimensions               #(do #js {"width"  (.-offsetWidth menuItemContainer)
                                                 "height" (.-offsetHeight menuItemContainer)})
     :hasAnchor                        #(this-as this (some-> (gobj/get this "root")
                                                              (gobj/get "parentElement")
                                                              (classes/has "mdc-menu-anchor")))
     :getAnchorDimensions              #(this-as this (some-> (gobj/get this "root")
                                                              (gobj/get "parentElement")
                                                              (.getBoundingClientRect)))
     :getWindowDimensions              #(do #js {"width"  (.-innerWidth Window)
                                                 "height" (.-innerHeight Window)})
     :setScale                         (fn [x y]
                                         (swap! state assoc-in [:mdc/root-styles (mdc-util/getTransformPropertyName)] (str "scale(" x ", " y ")")))
     :setInnerScale                    (fn [x y]
                                         (swap! state assoc-in [:mdc/inner-styles (mdc-util/getTransformPropertyName)] (str "scale(" x ", " y ")")))
     :getNumberOfItems                 #(aget (gdom/getChildren (get-container)) "length")
     :getYParamsForItemAtIndex         (fn [index]
                                         (let [^js/HTMLElement child (aget (gdom/getChildren (get-container)) index)]
                                           #js {"top"    (.-offsetTop child)
                                                "height" (.-offsetHeight child)}))
     :setTransitionDelayForItemAtIndex (fn [index value]
                                         (let [^js/CSSStyleDeclaration style (-> (aget (gdom/getChildren (get-container)) index)
                                                                                 (gobj/get "style"))]
                                           (.setProperty style "transition-delay" value)))
     :getIndexForEventTarget           (fn [target]
                                         (index-of (gdom/getChildren (get-container)) target))
     :getAttributeForEventTarget       (fn [^js/EventTarget target attr]
                                         (.getAttribute target attr))
     :notifySelected                   (fn [evtData]
                                         (when-let [f (get component :on-selected)]
                                           (f evtData)))
     :notifyCancel                     (fn [evtData]
                                         (when-let [f (get component :on-cancel)]
                                           (f evtData)))
     :saveFocus                        #(this-as this (aset this "previousFocus" (.-activeElement Document)))
     :restoreFocus                     #(this-as this (when-let [^js/HTMLElement prev-focus (aget this "previousFocus")]
                                                        (.focus prev-focus)))
     :isFocused                        #(= (.-activeElement Document) root)
     :focus                            #(.focus root)
     :getFocusedItemIndex              #(index-of (gdom/getChildren (get-container)) (.-activeElement Document))
     :focusItemAtIndex                 #(let [^js/HTMLElement el (aget (gdom/getChildren (get-container)) %)]
                                          (.focus el))
     :isRtl                            #(get component :rtl)
     :setTransformOrigin               #(swap! state assoc-in [:mdc/root-styles (str (mdc-util/getTransformPropertyName) "-origin")] %)
     :setPosition                      (fn [pos]
                                         (swap! state update :mdc/root-styles merge (reduce (fn [m k]
                                                                                              (assoc m k (or (aget pos k) nil))) {} ["left" "right" "top" "bottom"])))
     :getAccurateTime                  #(.. js/window -performance (now))
     :registerBodyClickHandler         #(.addEventListener Body "click" %)
     :deregisterBodyClickHandler       #(.removeEventListener Body "click" %)}))

(defn log-ret [msg x]
  (.log js/console msg x)
  x)

(defadapter Toolbar
  toolbar/MDCToolbarFoundation
  [{:keys [with-content] :as component}]
  (let [^js/HTMLElement toolbar-element (cond-> (v/dom-node component)
                                                with-content (gdom/getFirstElementChild))
        ^js/HTMLElement first-row-element (-> toolbar-element
                                              (gdom/findNode #(classes/has % "mdc-toolbar__row")))
        ^js/Window parent-window (or (some-> toolbar-element (gobj/get "ownerDocument") (gobj/get "defaultView"))
                                     Window)]
    (cond-> {:root                           toolbar-element
             :firstRowElement                first-row-element
             :titleElement                   (util/find-node toolbar-element #(classes/has % "mdc-toolbar__title"))


             :registerScrollHandler          (interaction-handler :listen parent-window "scroll")
             :deregisterScrollHandler        (interaction-handler :unlisten parent-window "scroll")

             :registerResizeHandler          (interaction-handler :listen parent-window "resize")
             :deregisterResizeHandler        (interaction-handler :unlisten parent-window "resize")
             :getViewportWidth               #(gobj/get parent-window "innerWidth")
             :getViewportScrollY             #(gobj/get parent-window "pageYOffset")
             :getOffsetHeight                #(gobj/get toolbar-element "offsetHeight")
             :getFirstRowElementOffsetHeight #(gobj/get first-row-element "offsetHeight")
             :notifyChange                   (fn [ratio])
             :setStyle                       (style-handler :Toolbar)
             :setStyleForTitleElement        (fn [attr val]
                                               (this-as this
                                                 (util/add-styles (gobj/get this "titleElement") {attr val})))
             :setStyleForFlexibleRowElement  (style-handler :Toolbar :firstRowElement)}
            with-content (merge {:fixedAdjustElement            (gdom/getNextElementSibling toolbar-element)
                                 :setStyleForFixedAdjustElement (style-handler :Toolbar :fixedAdjustElement)
                                 }))))