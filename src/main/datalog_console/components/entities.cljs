(ns datalog-console.components.entities
  {:no-doc true}
  (:require [datascript.core :as d]
            [cljs.reader]
            [goog.object]
            [homebase.reagent :as hbr]))


(defn ^:export entity-agg [db]
  (let [[query-result] (hbr/q '[:find (pull ?e [*])
                                :where [?e _ _]]
                              db)]
    (group-by :db/id (flatten @query-result))))


(defn entities []
  (fn [conn entity-lookup-ratom]
    (let [truncate-long-strings #(map (fn [[k v]]
                                        {k (if (and (string? v) (< 100 (count v)))
                                             (str (subs v 0 100) "...")
                                             v)}) %)]
      [:ul {:class "w-full flex flex-col pb-5"}
       (doall
        (for [[id] (entity-agg conn)]
          (let [[pull-result] (hbr/pull conn '[*] id)
                updated-pull-result (reduce-kv (fn [m k v]
                                                 (assoc m k (if (vector? v) (set v) v))) {} @pull-result)]
            ^{:key id}
            [:li
             {:class "odd:bg-gray-100 cursor-pointer min-w-max"
              :title (str (into {:db/id id} updated-pull-result))
              :on-click #(reset! entity-lookup-ratom (str id))}
             (str (into {:db/id id} (truncate-long-strings updated-pull-result)))])))])))


