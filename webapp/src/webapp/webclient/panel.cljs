(ns webapp.webclient.panel
  (:require
   ["@codemirror/commands" :as cm-commands]
   ["@codemirror/lang-sql" :refer [MSSQL MySQL PLSQL PostgreSQL sql]]
   ["@codemirror/language" :as cm-language]
   ["@codemirror/legacy-modes/mode/clojure" :as cm-clojure]
   ["@codemirror/legacy-modes/mode/javascript" :as cm-javascript]
   ["@codemirror/legacy-modes/mode/python" :as cm-python]
   ["@codemirror/legacy-modes/mode/ruby" :as cm-ruby]
   ["@codemirror/legacy-modes/mode/shell" :as cm-shell]
   ["@codemirror/state" :as cm-state]
   ["@codemirror/view" :as cm-view]
   ["@heroicons/react/20/solid" :as hero-solid-icon]
   ["@radix-ui/themes" :refer [Button Text]]
   ["@uiw/codemirror-theme-dracula" :refer [dracula]]
   ["@uiw/codemirror-theme-github" :refer [githubDark]]
   ["@uiw/codemirror-theme-nord" :refer [nord]]
   ["@uiw/codemirror-theme-sublime" :refer [sublime]]
   ["@uiw/react-codemirror" :as CodeMirror]
   ["allotment" :refer [Allotment]]
   ["codemirror-copilot" :refer [clearLocalCache inlineCopilot]]
   [clojure.string :as cs]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [webapp.components.forms :as forms]
   [webapp.config :as config]
   [webapp.formatters :as formatters]
   [webapp.jira-templates.loading-jira-templates :as loading-jira-templates]
   [webapp.jira-templates.prompt-form :as prompt-form]
   [webapp.subs :as subs]
   [webapp.webclient.aside.main :as aside]
   [webapp.webclient.codemirror.extensions :as extensions]
   [webapp.webclient.exec-multiples-connections.exec-list :as multiple-connections-exec-list-component]
   [webapp.webclient.log-area.main :as log-area]
   [webapp.webclient.quickstart :as quickstart]
   [webapp.webclient.runbooks.form :as runbooks-form]))

(defn discover-connection-type [connection]
  (cond
    (not (cs/blank? (:subtype connection))) (:subtype connection)
    (not (cs/blank? (:icon_name connection))) (:icon_name connection)
    :else (:type connection)))

(defn metadata->json-stringify
  [metadata]
  (->> metadata
       (filter (fn [{:keys [key value]}]
                 (not (or (cs/blank? key) (cs/blank? value)))))
       (map (fn [{:keys [key value]}] {key value}))
       (reduce into {})
       (clj->js)))

(defn- get-code-from-localstorage []
  (let [item (.getItem js/localStorage :code-tmp-db)
        object (js->clj (.parse js/JSON item))]
    (or (get object "code") "")))

(def ^:private timer (r/atom nil))
(def ^:private code-saved-status (r/atom :saved)) ; :edited | :saved

(defn- save-code-to-localstorage [code-string]
  (let [code-tmp-db {:date (.now js/Date)
                     :code code-string}
        code-tmp-db-json (.stringify js/JSON (clj->js code-tmp-db))]
    (.setItem js/localStorage :code-tmp-db code-tmp-db-json)
    (reset! code-saved-status :saved)))

(defn- auto-save [^cm-view/ViewUpdate view-update script]
  (when (.-docChanged view-update)
    (reset! code-saved-status :edited)
    (let [code-string (.toString (.-doc (.-state (.-view view-update))))]
      (when @timer (js/clearTimeout @timer))
      (reset! timer (js/setTimeout #(save-code-to-localstorage code-string) 1000))
      (reset! script code-string))))

(defn- needs-form? [template]
  (let [has-prompts? (seq (get-in template [:data :prompt_types :items]))
        has-cmdb? (when-let [cmdb-items (get-in template [:data :cmdb_types :items])]
                    (some (fn [{:keys [value jira_values]}]
                            (when (and value jira_values)
                              (not-any? #(= value (:name %)) jira_values)))
                          cmdb-items))]
    (or has-prompts? has-cmdb?)))

(defn- submit-task [e script selected-connections atom-exec-list-open? metadata reset-metadata script-response]
  (let [connection (first selected-connections)
        needs-template? (boolean (and connection
                                      (not (cs/blank? (:jira_issue_template_id connection)))))

        connection-type (discover-connection-type connection)
        multiple-connections? (> (count selected-connections) 1)
        has-jira-template-multiple-connections? (some
                                                 #(not (cs/blank? (:jira_issue_template_id %)))
                                                 selected-connections)
        jira-integration-enabled? @(rf/subscribe [:jira-integration->integration-enabled?])
        change-to-tabular? (and (some (partial = connection-type) ["mysql" "postgres" "sql-server" "oracledb" "mssql" "database"])
                                (< (count @script-response) 1))
        selected-db (.getItem js/localStorage "selected-database")
        final-script (cond
                       (and selected-db
                            (= connection-type "postgres")) (str "\\set QUIET on\n"
                                                                 "\\c " selected-db "\n"
                                                                 "\\set QUIET off\n"
                                                                 script)
                       (and selected-db
                            (= connection-type "mongodb")) (str "use " selected-db ";\n" script)
                       :else script)]

    (letfn [(handle-submit [form-data]
              (rf/dispatch [:modal->close])
              (let [exec-data {:script final-script
                               :connection-name (:name connection)
                               :metadata (metadata->json-stringify metadata)}
                    exec-data-with-fields (cond-> exec-data
                                            (:jira_fields form-data) (assoc :jira_fields (:jira_fields form-data))
                                            (:cmdb_fields form-data) (assoc :cmdb_fields (:cmdb_fields form-data)))]
                (rf/dispatch [:editor-plugin->exec-script exec-data-with-fields])
                (reset-metadata)))
            (check-template-and-show-form []
              (let [template @(rf/subscribe [:jira-templates->submit-template])]
                (if (or (nil? (:data template))
                        (= :loading (:status template)))
                  (js/setTimeout check-template-and-show-form 500)
                  (if (needs-form? template)
                    (rf/dispatch [:modal->open
                                  {:content [prompt-form/main
                                             {:prompts (get-in template [:data :prompt_types :items])
                                              :cmdb-items (get-in template [:data :cmdb_types :items])
                                              :on-submit handle-submit}]}])
                    (handle-submit nil)))))

            (handle-jira-template []
              (rf/dispatch [:modal->open
                            {:maxWidth "540px"
                             :custom-on-click-out (fn [event] (.preventDefault event))
                             :content [loading-jira-templates/main]}])
              (rf/dispatch [:jira-templates->get-submit-template
                            (:jira_issue_template_id connection)])

              (js/setTimeout check-template-and-show-form) 1000)]

      (when (.-preventDefault e) (.preventDefault e))

      (cond
        ;; Multiple connections check
        (and multiple-connections?
             has-jira-template-multiple-connections?
             jira-integration-enabled?)
        (rf/dispatch [:dialog->open
                      {:title "Running in multiple connections not allowed"
                       :action-button? false
                       :text [:> Text {:size "3" :class "text-[--gray-11]"}
                              (str "For now, it's not possible to run commands in multiple "
                                   "connections with Jira Templates activated. Please select "
                                   "just one connection before running your command.")]}])

        ;; Multiple connections without template
        (and (seq selected-connections) multiple-connections?)
        (reset! atom-exec-list-open? true)

        ;; Single connection
        (= (count selected-connections) 1)
        (if (and needs-template? jira-integration-enabled?)
          (handle-jira-template)

          (do
            (when change-to-tabular?
              (reset! log-area/selected-tab "Tabular"))
            (rf/dispatch [:editor-plugin->exec-script
                          {:script final-script
                           :connection-name (:name connection)
                           :metadata (metadata->json-stringify metadata)}])
            (reset-metadata)))

        :else
        (rf/dispatch [:show-snackbar
                      {:level :info
                       :text "You must choose a connection"}])))))

(defmulti ^:private saved-status-el identity)
(defmethod ^:private saved-status-el :saved [_]
  [:div {:class "flex flex-row-reverse"}
   [:div {:class "flex items-center gap-small"}
    [:> hero-solid-icon/CheckIcon {:class "h-4 w-4 shrink-0 text-green-500"
                                   :aria-hidden "true"}]
    [:span {:class "text-xs text-gray-300"}
     "Saved!"]]])
(defmethod ^:private saved-status-el :edited [_]
  [:div {:class "flex flex-row-reverse"}
   [:div {:class "flex items-center gap-small"}
    [:figure {:class "w-3"}
     [:img {:src (str config/webapp-url "/icons/icon-loader-circle-white.svg")
            :class "animate-spin"}]]
    [:span {:class "text-xs text-gray-300 italic"}
     "Edited"]]])

(defn process-schema [tree schema-key prefix]
  (reduce
   (fn [acc table-key]
     (let [qualified-key (if prefix
                           (str schema-key "." table-key)
                           table-key)]
       (assoc acc qualified-key (keys (get (get (:schema-tree tree) schema-key) table-key)))))
   {}
   (keys (get (:schema-tree tree) schema-key))))

(defn convert-tree [tree]
  (let [schema-keys (keys (:schema-tree tree))]
    (cond
      (> (count schema-keys) 1) (reduce
                                 (fn [acc schema-key]
                                   (merge acc (process-schema tree schema-key true)))
                                 {}
                                 schema-keys)
      (<= (count schema-keys) 1) (process-schema tree (first schema-keys) false)
      :else #js{})))

(defn- editor []
  (let [user (rf/subscribe [:users->current-user])
        db-connections (rf/subscribe [:connections])
        run-connections-list (rf/subscribe [:editor-plugin->run-connection-list])
        filtered-run-connections-list (rf/subscribe [:editor-plugin->filtered-run-connection-list])
        run-connection-list-selected (rf/subscribe [:editor-plugin->run-connection-list-selected])
        database-schema (rf/subscribe [::subs/database-schema])
        plugins (rf/subscribe [:plugins->my-plugins])
        selected-template (rf/subscribe [:runbooks-plugin->selected-runbooks])
        script-response (rf/subscribe [:editor-plugin->script])

        vertical-pane-sizes (mapv js/parseInt
                                  (cs/split
                                   (or (.getItem js/localStorage "editor-vertical-pane-sizes") "250,950") ","))
        horizontal-pane-sizes (mapv js/parseInt
                                    (cs/split
                                     (or (.getItem js/localStorage "editor-horizontal-pane-sizes") "650,210") ","))
        script (r/atom (get-code-from-localstorage))
        select-theme (r/atom "dracula")
        metadata (r/atom [])
        metadata-key (r/atom "")
        metadata-value (r/atom "")
        languages-options [{:text "Shell" :value "command-line"}
                           {:text "MySQL" :value "mysql"}
                           {:text "Postgres" :value "postgres"}
                           {:text "SQL Server" :value "mssql"}
                           {:text "MongoDB" :value "mongodb"}
                           {:text "JavaScript" :value "nodejs"}
                           {:text "Python" :value "python"}
                           {:text "Ruby" :value "ruby-on-rails"}
                           {:text "Clojure" :value "clojure"}]
        theme-options [{:text "Dracula" :value "dracula"}
                       {:text "Nord" :value "nord"}
                       {:text "Sublime" :value "sublime"}
                       {:text "Github dark" :value "github-dark"}]]
    (rf/dispatch [:runbooks-plugin->clear-active-runbooks])

    (fn [{:keys [script-output]}]
      (let [is-mac? (>= (.indexOf (.toUpperCase (.-platform js/navigator)) "MAC") 0)
            is-one-connection-selected? (= 1 (count @run-connection-list-selected))
            last-connection-selected (last @run-connection-list-selected)
            feature-ai-ask (or (get-in @user [:data :feature_ask_ai]) "disabled")
            script-output-loading? (= (:status @script-output) :loading)
            get-plugin-by-name (fn [name] (first (filter #(= (:name %) name) @plugins)))
            current-connection last-connection-selected
            connection-name (:name current-connection)
            connection-type (discover-connection-type current-connection)
            current-connection-details (fn [connection]
                                         (first (filter #(= (:name connection) (:name %))
                                                        (:connections (get-plugin-by-name "editor")))))
            schema-disabled? (fn [connection]
                               (or (= (first (:config (current-connection-details connection)))
                                      "schema=disabled")
                                   (= (:access_schema connection) "disabled")))
            run-connections-list-selected @run-connection-list-selected
            run-connections-list-rest (filterv #(and (not (:selected %))
                                                     (not= (:name %) connection-name))
                                               @filtered-run-connections-list)
            reset-metadata (fn []
                             (reset! metadata [])
                             (reset! metadata-key "")
                             (reset! metadata-value ""))
            keymap [{:key "Mod-Enter"
                     :run (fn [_]
                            (submit-task
                             {}
                             @script
                             run-connections-list-selected
                             multiple-connections-exec-list-component/atom-exec-list-open?
                             (conj @metadata {:key @metadata-key :value @metadata-value})
                             reset-metadata
                             script-response))
                     :preventDefault true}
                    {:key "Mod-Shift-Enter"
                     :run (fn [^cm-state/StateCommand config]
                            (let [ranges (.-ranges (.-selection (.-state config)))
                                  from (.-from (first ranges))
                                  to (.-to (first ranges))]
                              (submit-task
                               {}
                               (.sliceString ^cm-state/Text (.-doc (.-state config)) from to)
                               run-connections-list-selected
                               multiple-connections-exec-list-component/atom-exec-list-open?
                               (conj @metadata {:key @metadata-key :value @metadata-value})
                               reset-metadata
                               script-response)))
                     :preventDefault true}
                    {:key "Alt-ArrowLeft"
                     :mac "Ctrl-ArrowLeft"
                     :run cm-commands/cursorSyntaxLeft
                     :shift cm-commands/selectSyntaxLeft}
                    {:key "Alt-ArrowRight"
                     :mac "Ctrl-ArrowRight"
                     :run cm-commands/cursorSyntaxRight
                     :shift cm-commands/selectSyntaxRight}
                    {:key "Alt-ArrowUp" :run cm-commands/moveLineUp}
                    {:key "Shift-Alt-ArrowUp" :run cm-commands/copyLineUp}
                    {:key "Alt-ArrowDown" :run cm-commands/moveLineDown}
                    {:key "Shift-Alt-ArrowDown" :run cm-commands/copyLineDown}
                    {:key "Escape" :run cm-commands/simplifySelection}
                    {:key "Alt-l" :mac "Ctrl-l" :run cm-commands/selectLine}
                    {:key "Mod-i" :run cm-commands/selectParentSyntax :preventDefault true}
                    {:key "Mod-[" :run cm-commands/indentLess :preventDefault true}
                    {:key "Mod-]" :run cm-commands/indentMore :preventDefault true}
                    {:key "Mod-Alt-\\" :run cm-commands/indentSelection}
                    {:key "Shift-Mod-k" :run cm-commands/deleteLine}
                    {:key "Shift-Mod-\\" :run cm-commands/cursorMatchingBracket}
                    {:key "Mod-/" :run cm-commands/toggleComment}
                    {:key "Alt-A" :run cm-commands/toggleBlockComment}]
            current-schema (get-in @database-schema [:data connection-name])
            language-parser-case (let [subtype (:subtype (last run-connections-list-selected))
                                       databse-schema-sanitized (if (= (:status current-schema) :success)
                                                                  current-schema
                                                                  {:status :failure :raw "" :schema-tree []})
                                       schema (if (and is-one-connection-selected?
                                                       (= subtype (:type current-schema)))
                                                #js{:schema (clj->js (convert-tree databse-schema-sanitized))}
                                                #js{})]
                                   (case subtype
                                     "postgres" [(sql
                                                  (.assign js/Object (.-dialect PostgreSQL)
                                                           schema))]
                                     "mysql" [(sql
                                               (.assign js/Object (.-dialect MySQL)
                                                        schema))]
                                     "mssql" [(sql
                                               (.assign js/Object (.-dialect MSSQL)
                                                        schema))]
                                     "oracledb" [(sql
                                                  (.assign js/Object (.-dialect PLSQL)
                                                           schema))]
                                     "command-line" [(.define cm-language/StreamLanguage cm-shell/shell)]
                                     "javascript" [(.define cm-language/StreamLanguage cm-javascript/javascript)]
                                     "nodejs" [(.define cm-language/StreamLanguage cm-javascript/javascript)]
                                     "mongodb" [(.define cm-language/StreamLanguage cm-javascript/javascript)]
                                     "ruby-on-rails" [(.define cm-language/StreamLanguage cm-ruby/ruby)]
                                     "python" [(.define cm-language/StreamLanguage cm-python/python)]
                                     "clojure" [(.define cm-language/StreamLanguage cm-clojure/clojure)]
                                     "" [(.define cm-language/StreamLanguage cm-shell/shell)]
                                     [(.define cm-language/StreamLanguage cm-shell/shell)]))
            theme-parser-map {"dracula" dracula
                              "nord" nord
                              "github-dark" githubDark
                              "sublime" sublime
                              "" dracula
                              nil dracula}
            show-tree? (fn [connection]
                         (or (= (:type connection) "mysql-csv")
                             (= (:type connection) "postgres-csv")
                             (= (:type connection) "mongodb")
                             (= (:type connection) "postgres")
                             (= (:type connection) "mysql")
                             (= (:type connection) "sql-server-csv")
                             (= (:type connection) "mssql")
                             (= (:type connection) "oracledb")
                             (= (:type connection) "database")))]

        (if (and (empty? (:results @db-connections))
                 (not (:loading @db-connections)))
          [quickstart/main]

          [:div {:class "h-full flex flex-col"}
           [:div {:class "h-16 border border-gray-600 flex justify-end items-center gap-small px-4"}
            [:div {:class "flex items-center gap-small"}
             [:span {:class "text-xxs text-gray-500"}
              (str (if is-mac?
                     "(Cmd+Enter)"
                     "(Ctrl+Enter)"))]
             [:div {:class "flex"}
              [:> Button {:color "indigo"
                          :variant "solid"
                          :radius "medium"
                          :size "2"
                          :class-name "dark"
                          :disabled (or script-output-loading?
                                        (empty? run-connections-list-selected))
                          :on-click (fn [res]
                                      (submit-task
                                       res
                                       @script
                                       run-connections-list-selected
                                       multiple-connections-exec-list-component/atom-exec-list-open?
                                       (conj @metadata {:key @metadata-key :value @metadata-value})
                                       reset-metadata
                                       script-response))}
               [:div {:class "flex items-center gap-small"}
                [:> hero-solid-icon/PlayIcon {:class "h-3 w-3 text-inherit"
                                              :aria-hidden "true"}]
                [:span "Run"]]]]]]
           [:> Allotment {:defaultSizes vertical-pane-sizes
                          :onDragEnd #(.setItem js/localStorage "editor-vertical-pane-sizes" (str %))}
            [:> (.-Pane Allotment) {:minSize 250}
             [aside/main {:show-tree? show-tree?
                          :current-connection current-connection
                          :atom-run-connections-list run-connections-list
                          :atom-filtered-run-connections-list filtered-run-connections-list
                          :run-connections-list-selected run-connections-list-selected
                          :run-connections-list-rest run-connections-list-rest
                          :metadata metadata
                          :metadata-key metadata-key
                          :metadata-value metadata-value
                          :schema-disabled? schema-disabled?}]]
            [:> Allotment {:defaultSizes horizontal-pane-sizes
                           :onDragEnd #(.setItem js/localStorage "editor-horizontal-pane-sizes" (str %))
                           :vertical true}
             (if (= (:status @selected-template) :ready)
               [:section {:class "relative h-full p-3 overflow-auto"}
                [runbooks-form/main {:runbook @selected-template
                                     :preselected-connection (:name current-connection)
                                     :selected-connections (filter #(:selected %) (:data @run-connections-list))}]]



               [:> CodeMirror/default {:value @script
                                       :height "100%"
                                       :className "h-full text-sm"
                                       :theme (get theme-parser-map @select-theme)
                                       :basicSetup #js{:defaultKeymap false}
                                       :extensions (clj->js
                                                    (concat
                                                     (when (and (= feature-ai-ask "enabled")
                                                                is-one-connection-selected?)
                                                       [(inlineCopilot
                                                         (fn [prefix suffix]
                                                           (extensions/fetch-autocomplete
                                                            (:subtype (last run-connections-list-selected))
                                                            prefix
                                                            suffix
                                                            (:raw current-schema))))])
                                                     [(.of cm-view/keymap (clj->js keymap))]
                                                     language-parser-case
                                                     (when (= (:status @selected-template) :ready)
                                                       [(.of (.-editable cm-view/EditorView) false)
                                                        (.of (.-readOnly cm-state/EditorState) true)])))
                                       :onUpdate #(auto-save % script)}])

             [log-area/main connection-type connection-name is-one-connection-selected? (show-tree? current-connection)]]]
           [:div {:class "border border-gray-600"}
            [:footer {:class "flex justify-between items-center p-small gap-small"}
             [:div {:class "flex items-center gap-small"}
              [saved-status-el @code-saved-status]
              (when (:execution_time (:data @script-output))
                [:div {:class "flex items-center gap-small"}
                 [:> hero-solid-icon/ClockIcon {:class "h-4 w-4 shrink-0 text-white"
                                                :aria-hidden "true"}]
                 [:span {:class "text-xs text-gray-300"}
                  (str "Last execution time " (formatters/time-elapsed (:execution_time (:data @script-output))))]])]
             [:div {:class "flex items-center gap-regular mr-16"}
              [forms/select-editor {:on-change #(reset! select-theme %)
                                    :selected (or @select-theme "")
                                    :options theme-options}]
              [forms/select-editor {:on-change #(rf/dispatch [:editor-plugin->set-select-language %])
                                    :selected (or (cond
                                                    (not (cs/blank? (:subtype (last run-connections-list-selected)))) (:subtype (last run-connections-list-selected))
                                                    (not (cs/blank? (:icon_name (last run-connections-list-selected)))) (:icon_name (last run-connections-list-selected))
                                                    (= (:type (last run-connections-list-selected)) "custom") "command-line"
                                                    :else (:type (last run-connections-list-selected))) "")
                                    :options languages-options}]]]]

           (when @multiple-connections-exec-list-component/atom-exec-list-open?
             [multiple-connections-exec-list-component/main
              (map #(into {} {:connection-name (:name %)
                              :script @script
                              :metadata (metadata->json-stringify
                                         (conj @metadata {:key @metadata-key :value @metadata-value}))
                              :type (:type %)
                              :subtype (:subtype %)
                              :session-id nil
                              :status :ready})
                   run-connections-list-selected)
              reset-metadata])])))))

(defn main []
  (let [script-response (rf/subscribe [:editor-plugin->script])]
    (rf/dispatch [:editor-plugin->clear-script])
    (rf/dispatch [:editor-plugin->clear-connection-script])
    (rf/dispatch [:ask-ai->clear-ai-responses])
    (rf/dispatch [:connections->get-connections])
    (rf/dispatch [:audit->clear-session])
    (rf/dispatch [:plugins->get-my-plugins])
    (rf/dispatch [:jira-templates->get-all])
    (rf/dispatch [:jira-integration->get])
    (fn []
      (clearLocalCache)
      (rf/dispatch [:editor-plugin->get-run-connection-list])
      [editor {:script-output script-response}])))
