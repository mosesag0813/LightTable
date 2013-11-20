(ns lt.objs.workspace
  (:require [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.command :as cmd]
            [lt.objs.window :as window]
            [cljs.reader :as reader]
            [lt.util.load :as load]
            [lt.util.js :refer [now]]
            [lt.util.cljs :refer [->dottedkw js->clj]]))

;;*********************************************************
;; Watching
;; TODO: The way I did this is awful. Should get cleaned up
;;*********************************************************

(def fs (js/require "fs"))
(def max-depth 10)
(def watch-interval 1000)

(defn unwatch [watches path recursive?]
  (when watches
    (let [removes (cond
                    (coll? path) path
                    (not recursive?) [path]
                    :else (filter #(> (.indexOf % path) -1) (keys watches)))]
      (doseq [r (map watches removes)
              :when (and r (:close r))]
        ((:close r)))
      (apply dissoc watches removes))))

(defn alert-file [path]
  (fn [cur prev]
    (if (.existsSync fs path)
      (do
        (object/raise current-ws :watched.update path cur))
      (do
        (unwatch! path)
        (object/raise current-ws :watched.delete path)))))

(defn alert-folder [path]
  (fn [cur prev]
    (if (.existsSync fs path)
      (do
        (let [watches (:watches @current-ws)
              neue (first (filter #(and (not (get watches %))
                                        (not (re-seq files/ignore-pattern %)))
                                  (files/full-path-ls path)))]
          (when neue
            (watch! neue)
            (object/raise current-ws :watched.create neue (.statSync fs neue)))))
      (do
        (unwatch! path :recursive)
        (object/raise current-ws :watched.delete path)))))

(defn file->watch [path]
  (let [alert (alert-file path)]
    {:path path
     :alert alert
     :close (fn []
              (.unwatchFile fs path alert))}))

(defn folder->watch [path]
  (let [alert (alert-folder path)]
     {:path path
      :alert alert
      :close (fn []
              (.unwatchFile fs path alert))}))

(defn watch!
  ([path] (watch! (transient {}) path nil))
  ([path recursive?] (watch! (transient {}) path recursive?))
  ([results path recursive?]
   (doseq [path (if (coll? path)
                  path
                  [path])]
     (when-not (re-seq files/ignore-pattern path)
       (if (files/dir? path)
         (let [recursive? (cond
                           (not recursive?) 0
                           (number? recursive?) (dec recursive?)
                           :else max-depth)
               watch (folder->watch path)]
           (when-not (get (:watches @current-ws) path)
             (assoc! results path watch)
             (.watchFile fs path (js-obj "interval" watch-interval
                                         "persistent" false)
                         (:alert watch)))
           (when (> recursive? -1)
             (watch! results (files/full-path-ls path) recursive?)))
         (when (and (not (get (:watches @current-ws) path))
                    (not (get results path)))
           (let [watch (file->watch path)]
             (assoc! results path watch)
             (.watchFile fs path (js-obj "interval" watch-interval
                                         "persistent" false)
                         (:alert watch)))))))
     (when-not (number? recursive?)
       (object/update! current-ws [:watches] merge (persistent! results)))))

(defn unwatch! [path recursive?]
  (object/merge! current-ws {:watches (unwatch (:watches @current-ws) path recursive?)}))

(defn stop-watching [ws]
  (unwatch! (keys (:watches @ws))))

(defn watch-workspace [ws]
  (stop-watching ws)
  (watch! (object/raise-reduce ws :watch-paths+ [])))

;;*********************************************************
;; Files and folders
;;*********************************************************

(defn files-and-folders [path]
  (reduce (fn [res cur]
            (let [dir? (files/dir? cur)]
              (if (re-seq files/ignore-pattern (str (files/basename cur) (when dir? files/separator)))
                res
                (if dir?
                  (update-in res [:folders] conj cur)
                  (update-in res [:files] conj cur)))))
          {:folders []
           :files []}
          (files/full-path-ls path)))

(defn serialize [ws]
  (select-keys ws [:files :folders :ws-behaviors]))

(defn reconstitute [ws v]
  (object/raise ws :set! {:files (:files v)
                          :folders (filter files/exists? (:folders v))
                          :ws-behaviors (:ws-behaviors v)}))

(defn add! [ws k v]
  (object/update! ws [k] conj v))

(defn remove! [ws k v]
  (object/update! ws [k] #(vec (remove #{v} %))))

(defn new-cached-file []
  (str (now) ".clj"))

(defn open [ws file]
  (let [loc (if-not (> (.indexOf file files/separator) -1)
              (files/lt-home (files/join "core" "cache" "workspace" file))
              file)]
    (object/merge! ws {:file (new-cached-file)})
    (try
      (reconstitute ws (file->ws loc))
      (save ws (:file @ws))
      (files/delete! loc)
      (catch js/Error e
        ))))

(defn save [ws file]
  (files/save (files/lt-home (files/join "core" "cache" "workspace" file)) (pr-str (serialize @ws)))
  (object/raise ws :save))

(defn cached []
  (filter #(> (.indexOf % ".clj") -1) (files/full-path-ls (files/lt-home (files/join "core" "cache" "workspace")))))

(defn file->ws [file]
  (-> (files/open-sync file)
      (:content)
      (reader/read-string)
      (assoc :path file)))

(defn all []
  (let [fs (sort > (cached))]
    ;;if there are more than 20, delete the extras
    (doseq [file (drop 20 fs)]
      (files/delete! file))
    (map file->ws (take 20 fs))))

(defn ws-empty? [ws]
  (not (or (seq (:files @ws))
           (seq (:folders @ws)))))

(object/behavior* ::serialize-workspace
                  :triggers #{:updated :serialize!}
                  :reaction (fn [this]
                              (when-not (@this :file)
                                (object/merge! this {:file (new-cached-file)}))
                              (when (and (@this :initialized?)
                                         (not (ws-empty? this)))
                                (save this (:file @this)))))

(object/behavior* ::reconstitute-last-workspace
                  :triggers #{:post-init}
                  :reaction (fn [app]
                              (when (and (= (window/window-number) 0)
                                         (not (:initialized @current-ws)))
                                (when-let [ws (first (all))]
                                  (open current-ws (-> ws :path (files/basename))))) ;;for backwards compat
                              (object/merge! current-ws {:initialized? true})))

(object/behavior* ::new!
                  :triggers #{:new!}
                  :reaction (fn [this]
                              (object/merge! this {:file (new-cached-file)})
                              (object/raise this :clear!)))

(object/behavior* ::add-file!
                  :triggers #{:add.file!}
                  :reaction (fn [this f]
                              (add! this :files f)
                              (object/raise this :add f)
                              (object/raise this :updated)))

(object/behavior* ::add-folder!
                  :triggers #{:add.folder!}
                  :reaction (fn [this f]
                              (add! this :folders f)
                              (object/raise this :add f)
                              (object/raise this :updated)))

(object/behavior* ::remove-file!
                  :triggers #{:remove.file!}
                  :reaction (fn [this f]
                              (remove! this :files f)
                              (object/raise this :remove f)
                              (object/raise this :updated)))

(object/behavior* ::remove-folder!
                  :triggers #{:remove.folder!}
                  :reaction (fn [this f]
                              (remove! this :folders f)
                              (object/raise this :remove f)
                              (object/raise this :updated)))

(object/behavior* ::rename!
                  :triggers #{:rename!}
                  :reaction (fn [this f neue]
                              (let [key (if (files/file? f)
                                          :files
                                          :folders)]
                                (remove! this key f)
                                (add! this key neue)
                                (object/raise this :rename f neue)
                                (object/raise this :updated))))

(object/behavior* ::clear!
                  :triggers #{:clear!}
                  :reaction (fn [this]
                              (let [old @this]
                                (object/merge! this {:files []
                                                     :folders []
                                                     :ws-behaviors ""})
                                (object/raise this :set old)
                                (object/raise this :updated))))

(object/behavior* ::set!
                  :triggers #{:set!}
                  :reaction (fn [this fs]
                              (let [old @this]
                                (object/merge! this fs)
                                (object/raise this :set old)
                                (object/raise this :updated))))

(object/behavior* ::watch-on-set
                  :triggers #{:set}
                  :reaction (fn [this]
                              (watch-workspace this)))

(object/behavior* ::stop-watch-on-close
                  :triggers #{:close :refresh}
                  :reaction (fn [app]
                              (stop-watching current-ws)))

(object/object* ::workspace
                :tags #{:workspace}
                :files []
                :folders []
                :watches {}
                :ws-behaviors ""
                :init (fn [this]
                        nil))

(def current-ws (object/create ::workspace))

(cmd/command {:command :workspace.new
              :desc "Workspace: Create new workspace"
              :exec (fn []
                      (object/raise current-ws :new!)
                      )})
