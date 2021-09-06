(ns datalog-console.components.entity-hbr
  {:no-doc true}
  (:require [datascript.core :as d]
            [reagent.core :as r]
            [cljs.reader]
            [goog.object]
            [clojure.string :as str]
            [homebase.reagent :as hbr]
            [datalog-console.components.tree-table :as c.tree-table]))

(defn update-pull-result [pull-result]
  (reduce-kv (fn [m k v]
               (assoc m k (if (vector? v) (set v) v))) {} @pull-result))

(defn entity? [v]
  (try
    (not (nil? (:db/id v)))
    (catch js/Error _e false)))

(defn expandable-row? [[_a v]]
  (if (set? v)
    (entity? (first v))
    (entity? v)))

(defn keyword->reverse-ref [kw]
  (keyword (str (namespace kw) "/_" (name kw))))

(defn ^:export reverse-refs [conn pull-result]
  (let [[query-result] (hbr/q '[:find ?ref-attr ?e
                                :in $ ?ref-id [?ref-attr ...]
                                :where [?e ?ref-attr ?ref-id]]
                              conn
                              (:db/id pull-result)
                              (for [[attr props] (:schema @conn)
                                    :when (= :db.type/ref (:db/valueType props))]
                                attr))]
    (reduce-kv (fn [acc k v]
                 (conj acc [(keyword->reverse-ref  k)
                            (set (for [[_ eid] v] (let [[pull-result] (hbr/pull conn '[*] eid)]
                                                    @pull-result)))]))
               []
               (group-by first @query-result))))

(defn entity->rows [conn pull-result]
  (concat
   [[:db/id (:db/id pull-result)]]
   (sort (seq (dissoc pull-result :db/id)))
   (sort (reverse-refs conn pull-result))))

(defn expand-row [conn [a v]]
  (cond
    (set? v) (map-indexed (fn [i vv] [(str a " " i) vv]) v)
    (entity? v) (entity->rows conn (let [[pull-result] (hbr/pull conn '[*] (:db/id v))]
                                     (update-pull-result pull-result)))))

(defn string-cell []
  (let [expanded-text? (r/atom false)]
    (fn [s]
      (if (< (count s) 45)
        [:div s]
        [:div {:class (str "cursor-pointer " (if-not @expanded-text? "min-w-max" "w-96"))
               :on-click #(reset! expanded-text? (not @expanded-text?))}
         (if @expanded-text? s (str (subs s 0 45) "..."))]))))


(defn render-col [col]
  (cond
    (set? col) (str "#[" (count col) " item" (when (< 1 (count col)) "s") "]")
    (entity? col) (str (select-keys col [:db/id]))
    (string? col) [string-cell col]
    :else (str col)))

(defn lookup-form []
  (let [lookup (r/atom "")
        input-error (r/atom nil)]
    (fn [conn on-submit]
      [:div
       [:form {:class "flex items-end"
               :on-submit
               (fn [e]
                 (.preventDefault e)
                 (try
                   (hbr/entity conn (cljs.reader/read-string @lookup))
                   (on-submit @lookup)
                   (reset! lookup "")
                   (reset! input-error nil)
                   (catch js/Error e
                     (reset! input-error (goog.object/get e "message")))))}
        [:label {:class "block pl-1"}
         [:p {:class "font-bold"} "Entity lookup"]
         [:input {:type "text"
                  :name "lookup"
                  :value @lookup
                  :on-change (fn [e] (reset! lookup (goog.object/getValueByKeys e #js ["target" "value"])))
                  :placeholder "id or [:uniq-attr1 \"v1\" ...]"
                  :class "border py-1 px-2 rounded w-56"}]]
        [:button {:type "submit"
                  :class "ml-1 py-1 px-2 rounded bg-gray-200 border"}
         "Get entity"]]
       (when @input-error
         [:div {:class "bg-red-200 m-4 p-4 rounded"}
          [:p @input-error]])])))

(defn entity []
  (fn [conn entity-lookup-ratom]
    (let [[pull-result] (if-not (empty? @entity-lookup-ratom)
                          (hbr/pull conn '[*] (cljs.reader/read-string @entity-lookup-ratom))
                          [false])]
      [:div {:class "flex flex-col w-full pb-5"}
       [lookup-form conn #(reset! entity-lookup-ratom %)]
       (when pull-result
         [:div {:class "pt-2"}
          (when entity
            [c.tree-table/tree-table
             {:caption (str "entity " (select-keys @pull-result [:db/id]))
              :conn conn
              :head-row ["Attribute", "Value"]
              :rows (entity->rows conn (update-pull-result pull-result))
              :expandable-row? expandable-row?
              :expand-row (partial expand-row conn)
              :render-col render-col}])])])))


