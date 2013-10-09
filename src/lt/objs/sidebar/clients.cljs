(ns lt.objs.sidebar.clients
  (:require [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.sidebar :as sidebar]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.clients.tcp :as tcp]
            [lt.objs.clients.ws :as ws]
            [lt.objs.popup :as popup]
            [lt.objs.editor.pool :as pool]
            [lt.util.dom :as dom]
            [crate.binding :refer [bound map-bound subatom]])
  (:require-macros [lt.macros :refer [defui]]))

(defui close-button [i]
  [:span.button "disconnect"]
  :click (fn []
           (clients/close! i)))

(defui unset-button [i]
  [:span.button.unset "unset"]
  :click (fn []
           (object/raise clients :unset! i)))

(defui add-button [this]
  [:h2.toggle.add "Add Connection"]
  :click (fn []
           (object/raise this :selecting!)))

(defui choose-cancel [this]
  [:h2.toggle [:span "Choose a client type"]]
  :click (fn []
           (object/raise this :cancel)))

(defn ->active? [cur clients]
  (let [actives (:active clients)
        found? (first (filter #(= cur (val %)) actives))]
    (if found?
      (str "active client-" (name (first found?)))
      "")))

(defn client-item* [i]
  (if (:connected @i)
    [:div
     [:h2 (:name @i)]
     [:div.details
      [:table
       [:tr
        [:td "Type"] [:td (:type @i)]]
       [:tr
        [:td "Commands"] [:td
                          [:ul
                           (for [c (:commands @i)]
                             [:li c])]]]]
      (close-button i)
      (unset-button i)]]
    [:div.connecting
     [:div.load-wrapper [:div.img]]
     [:p "Connecting.."]]
    ))

(defn connector? [clients]
  (str "clients "
       (if (:selecting? clients)
         "selecting"
         "")
       (when (dom/has-class? (:content clients) :active)
         " active")))

(defui client-item [clients i]
  (let [i @i]
    [:li {:class (bound clients #(->active? i %))}
     (bound i #(client-item* i))
     ]))

(defui connection-type [this i]
  [:li
   [:h2 (i :name)]
   [:p (i :desc)]
   ]
  :click (fn []
           (object/raise this :selected)
           ((:connect i))
           ))

(defui connect-ui [this]
  [:div {:class (bound this connector?)
         :tabindex -1}
   [:div.list
    (add-button this)
    [:ul
     (map-bound (partial client-item this) clients/cs)]]
   [:div.connector
    (choose-cancel this)
    [:ul
     (bound (subatom this [:connectors]) (partial connectors this))
     ]]]
  :focus (fn []
           (ctx/in! :sidebar.clients this))
  :blur (fn []
          (ctx/out! :sidebar.clients this))
  )

(object/behavior* ::track-active-client
                  :triggers #{:active :set-client}
                  :reaction (fn [ed]
                              (object/merge! clients {:active (:client @ed)})))

(object/behavior* ::unset-client
                  :triggers #{:unset!}
                  :reaction (fn [this cur]
                              (let [ed (pool/last-active)
                                    actives (:client @ed)
                                    found? (first (filter #(= cur (val %)) actives))]
                                (when found?
                                  (object/update! ed [:client] dissoc (first found?)))
                                (pool/focus-last))))

(object/behavior* ::selecting!
                  :triggers #{:selecting!}
                  :reaction (fn [this]
                              (object/merge! this {:selecting? true})
                              ))

(object/behavior* ::done-selecting
                  :triggers #{:selected :cancel}
                  :reaction (fn [this]
                              (object/merge! this {:selecting? false})))

(object/behavior* ::hide-on-select
                  :triggers #{:selected}
                  :reaction (fn [this]
                              (object/raise sidebar/rightbar :close!)))

(object/behavior* ::focus!
                  :triggers #{:focus!}
                  :reaction (fn [this]
                              (dom/focus (object/->content this))))

(defn connectors [this connectors]
  (for [[k c] connectors]
    (connection-type this c)
    ))

(object/object* ::sidebar.clients
                :tags #{:sidebar.clients}
                :label "connect"
                :connectors (sorted-map)
                :order 2
                :init (fn [this]
                        (connect-ui this)
                        ))

(def clients (object/create ::sidebar.clients))

(sidebar/add-item sidebar/rightbar clients)

(defn add-connector [c]
  (object/update! clients [:connectors] assoc (:name c) c))

(cmd/command {:command :show-connect
              :desc "Connect: Show connect bar"
              :exec (fn []
                      (object/raise sidebar/rightbar :toggle clients {:force? true
                                                                     :transient? false})
                      (object/raise clients :focus!)
                      )})


(cmd/command {:command :hide-connect
              :desc "Connect: hide connect bar"
              :exec (fn []
                      (object/raise sidebar/rightbar :close!)
                      )})

(cmd/command {:command :show-add-connection
              :desc "Connect: Add Connection"
              :exec (fn []
                      (object/raise sidebar/rightbar :toggle clients {:force? true
                                                                     :transient? false})
                      (object/raise clients :selecting!)
                      )})

(add-connector {:name "Ports"
                :desc "Get the ports for the local TCP and Websocket servers"
                :connect (fn []
                           (popup/popup! {:header "Ports"
                                          :body [:dl#ports
                                                 [:dt "TCP: "] [:dd (str tcp/port)]
                                                 [:dt "WebSocket: "] [:dd (str ws/port)]]
                                          :buttons [{:label "ok"}]}))})
