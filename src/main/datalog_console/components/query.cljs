(ns datalog-console.components.query
  (:require [datascript.core :as d]
            [reagent.core :as r]
            [cljs.reader]))


(defn query []
  (let [query-text (r/atom "")
        query-result (r/atom nil)
        query-error (r/atom nil)]
    (fn [conn]
      [:div {:class "m-4 pb-4"}
       [:form {:on-submit (fn [e]
                            (.preventDefault e)
                            (try (let [q-result (d/q (cljs.reader/read-string @query-text)
                                                     @conn)]
                                   (reset! query-error nil)
                                   (reset! query-result q-result))
                                 (catch js/Error e
                                   (reset! query-result nil)
                                   (reset! query-error (goog.object/get e "message")))))}
        
        [:div 
         [:div {:class "flex justify-between mb-2 w-1/2 items-baseline"
                :style {:min-width "20rem"}}
          [:p {:class "font-bold"} "Query Editor"]
          [:button {:type "submit"
                    :class "ml-1 py-1 px-2 rounded bg-gray-200 border"}
           "Run query"]]]
        [:textarea
         {:style {:min-width "20rem"}
          :class        "border w-1/2 p-2"
          :placeholder "[:find ?e ?a ?v \n :where \n [?e ?a ?v]]"
          :rows 3
          :value        @query-text
          :on-change    (fn [e]
                          (reset! query-text (goog.object/getValueByKeys e #js ["target" "value"]))
                          (js/console.log "this is a change"))}]]
       [:div {:class "w-1/2"
              :style {:min-width "20rem"}}
        (when @query-result
          [:di
           [:span "Query result:"]
           [:div {:class "border p-4 rounded"}
            [:span @query-result]]])
        (when @query-error
          [:div {:class "bg-red-200 p-4 rounded"}
           [:p @query-error]])]])))