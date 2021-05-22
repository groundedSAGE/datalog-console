(ns datalog-console.components.query
  (:require [datascript.core :as d]
            [reagent.core :as r]
            [cljs.reader]
            [clojure.set :refer [difference union]]
            ["@monaco-editor/react" :as Editor]))


(set! *warn-on-infer* true)

(defn create-attr-proposals [in-range conn monaco]
  (let [db @conn
        schema-attrs (keys (:schema db))
        inferred-attrs (difference (set (map second (d/datoms db :eavt))) (set schema-attrs))
        suggestion-constructor (fn [attrs extra-label]
                                 (set (map (fn [attr] {:label (str attr)
                                                        :detail extra-label
                                                       :insertText (str attr)})
                                           attrs)))
        all-attrs (union (suggestion-constructor schema-attrs " - schema attr")
                          (suggestion-constructor inferred-attrs " - inferred attr"))
        result (map #(merge % in-range)
                    (into [{:label ":find"
                            :insertText ":find"}
                           {:label ":where"
                            :insertText ":where"}]
                          all-attrs))]
    (clj->js result)))




(defn set-autocomplete [monaco conn]
  (let [m (.-languages monaco)
        provider #js {:provideCompletionItems (fn [^js/monaco.editor.ITextModel model position]
                                                (try (let [word (.getWordUntilPosition model position)
                                                           word-range {:startLineNumber (.-lineNumber position)
                                                                       :endLineNumber (.-lineNumber position)
                                                                       :startColumn (.-startColumn word)
                                                                       :endColumn (.-endColumn word)}]
                                                       #js {:suggestions (create-attr-proposals word-range conn m)})
                                                     (catch js/Error e (js/console.log (.-message e)))))
                      :provideHover (fn [^js/monaco.editor.ITextModel model position]
                                      (try (let [word (.getWordUntilPosition model position)
                                                 word-range {:startLineNumber (.-lineNumber position)
                                                             :endLineNumber (.-lineNumber position)
                                                             :startColumn (.-startColumn word)
                                                             :endColumn (.-endColumn word)}]
                                             (clj->js {:range word-range
                                                       :contents [{:value "hey"}]}))
                                           (catch js/Error e (js/console.log (.-message e)))))}]
    (m.registerCompletionItemProvider
     "clojure"
     provider)))

(defn c-editor []
  (fn [conn !ref]
    [:div {:style {:min-width "20rem"
                   ;:width "50%"
                   }}
     [:> Editor/default
      {:height "10rem"
       :value "[:find ?e ?a ?v \n :where \n [?e ?a ?v]]"
       :language "clojure"
       :theme "vs-dark"
       :options {:minimap {:enabled false}
                 :folding false}
       :onMount (fn [editor _monaco] (reset! !ref editor))
       :beforeMount (fn [monaco-instance] (set-autocomplete monaco-instance conn))}]]))



(defn query []
  (let [query-result (r/atom nil)
        query-error (r/atom nil)
        !ref (atom nil)]
    (fn [conn]
      [:div {:class "m-4 pb-4"}
       [:form {:on-submit (fn [e]
                            (.preventDefault e)
                            (try (let [q-result (d/q (cljs.reader/read-string (some-> @!ref .getValue))
                                                     @conn)]
                                   (reset! query-error nil)
                                   (reset! query-result q-result))
                                 (catch js/Error e
                                   (reset! query-result nil)
                                   (reset! query-error (goog.object/get e "message")))))}

        [:div
         [:div {:class "flex justify-between mb-2  items-baseline" ;w-1/2
                :style {:min-width "20rem"}}
          [:p {:class "font-bold"} "Query Editor"]
          [:button {:type "submit"
                    :class "ml-1 py-1 px-2 rounded bg-gray-200 border"}
           "Run query"]]]

        [c-editor conn !ref]

        #_[:textarea
           {:style {:min-width "20rem"}
            :class        "border w-1/2 p-2"
            :placeholder "[:find ?e ?a ?v \n :where \n [?e ?a ?v]]"
            :rows 3
            :value        @query-text
            :on-change    (fn [e]
                            (reset! query-text (goog.object/getValueByKeys e #js ["target" "value"])))}]]
       [:div {;:class "w-1/2"
              :style {:min-width "20rem"}}
        (when @query-result
          [:div
           [:span "Query result:"]
           [:div {:class "border p-4 rounded"}
            [:span @query-result]]])
        (when @query-error
          [:div {:class "bg-red-200 p-4 rounded"}
           [:p @query-error]])]])))