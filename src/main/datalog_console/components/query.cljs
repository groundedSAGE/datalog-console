(ns datalog-console.components.query
  (:require [datascript.core :as d]
            [reagent.core :as r]
            [cljs.reader]
            [cljs.pprint]))

(def example-querys 
  {"All attributes" "[:find [?attr ...] \n :where [_ ?attr]]"
   "All entities" "[:find ?e ?a ?v \n :where \n [?e ?a ?v]]"
   "Example and query" "[:find ?e \n :where \n [?e :attr1 \"value 1\"] \n [?e :attr2 \"value 2\"]]"})

(defn result []
  (let [sort-action (r/atom 0)]
    (fn [result]
      [:<>
       [:div {:class "flex flex-row justify-between items-baseline mt-4 mb-2 "}
        [:span (str "Query results: " (count result))]
        [:button {:class "ml-1 py-1 px-2 rounded bg-gray-200 border w-24"
                  :on-click #(swap! sort-action (fn [x] (mod (inc x) 3)))}
         (case @sort-action
           0 "Sort"
           1 "↓"
           2 "↑")]]
       [:div {:class "border p-4 rounded overflow-auto"}
        [:pre  (with-out-str (cljs.pprint/pprint (case @sort-action
                                                   0 result
                                                   1 (sort result)
                                                   2 (reverse (sort result)))))]]])))

(defn query []
  (let [query-text (r/atom (:all-attrs example-querys))
        query-result (r/atom nil)]
    (fn [conn]
      [:div {:class "px-1"}
       [:p {:class "font-bold"} "Query Editor"]
       [:div {:class "flex justify-between mb-2 items-baseline"
              :style {:min-width "20rem"}}
        [:div {:class "-ml-1"}
         (for [[k v] example-querys]
           ^{:key (str k)}
           [:button {:class "ml-1 mt-1 py-1 px-2 rounded bg-gray-200 border"
                     :on-click #(reset! query-text v)} k])]]
       [:form {:on-submit (fn [e]
                            (.preventDefault e)
                            (try
                              (reset! query-result {:success (d/q (cljs.reader/read-string @query-text) @conn)})
                              (catch js/Error e
                                (reset! query-result {:error (goog.object/get e "message")}))))}
        [:div {:class "flex flex-col"}
         [:textarea
          {:style {:min-width "20rem"}
           :class        "border p-2"
           :rows          5
           :value        @query-text
           :on-change    (fn [e]
                           (reset! query-text (goog.object/getValueByKeys e #js ["target" "value"])))}]
         [:button {:type "submit"
                   :class "py-1 px-2 rounded-b bg-gray-200 border"}
          "Run query"]]]
       [:div {:style {:min-width "20rem"}}
        (when-let [q-result (:success @query-result)]
          [result q-result])
        (when-let [q-error (:error @query-result)]
          [:div {:class "bg-red-200 p-4 rounded"}
           [:p q-error]])]])))