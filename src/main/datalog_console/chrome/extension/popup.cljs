(ns datalog-console.chrome.extension.popup
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [reagent.ratom]
            [datalog-console.client :as console]))


(defn root []
  (let [counter (r/atom 1)]
    (fn []
      [:div
       [:span "The counter" @counter]
       [:button {:on-click #(swap! counter inc)}
        "Increment"]])))


(defn mount! []
  (rdom/render [root] (js/document.getElementById "root")))

(defn init! []
  (mount!))

(defn ^:dev/after-load remount!
  "Remounts the whole UI on every save. Def state you want to persist between remounts with defonce."
  []
  (mount!))

(mount!)





;; (defn mount! []
;;   (rdom/render [root] (js/document.getElementById "root")))

;; (defn init! []
;;   (mount!))

;; (defn ^:dev/after-load remount!
;;   "Remounts the whole UI on every save. Def state you want to persist between remounts with defonce."
;;   []
;;   (mount!))