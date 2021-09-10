(ns datalog-console.electron.main
  {:no-doc true}
  (:require [clojure.edn]
            [cljs.reader]
            [reagent.dom :as rdom]
            [datalog-console.client :as console]))

(defn mount! []
  (rdom/render [console/root] (js/document.getElementById "root")))

(defn init! []
  (mount!))

(defn ^:dev/after-load remount!
  "Remounts the whole UI on every save. Def state you want to persist between remounts with defonce."
  []
  (mount!))

(mount!)