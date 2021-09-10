(ns datalog-console.electron.renderer.core
  (:require [reagent.dom :as r]
            [datalog-console.client]))


(defn ^:dev/after-load start! []
  (r/render
   [datalog-console.client/root]
   (js/document.getElementById "root")))