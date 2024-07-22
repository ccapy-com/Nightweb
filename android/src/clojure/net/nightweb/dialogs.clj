(ns net.nightweb.dialogs
  (:require [neko.resource :as r]
            [neko.threading :as thread]
            [neko.ui :as ui]
            [net.nightweb.actions :as actions]
            [net.nightweb.views-dialog :as views-dialog]
            [net.nightweb.utils :as utils]
            [nightweb.actions :as a]
            [nightweb.constants :as c]
            [nightweb.db :as db]
            [nightweb.io :as io]
            [nightweb.users :as users])
  (:import [android.app Activity AlertDialog DialogFragment]
           [android.content DialogInterface]
           [android.text InputType]
           [android.view View]
           [android.widget Button]))

(defn create-dialog
  [^Activity context ^String message ^View view buttons]
  (let [builder (android.app.AlertDialog$Builder. context)]
    (when-let [positive-name (:positive-name buttons)]
      (.setPositiveButton builder positive-name nil))
    (when-let [neutral-name (:neutral-name buttons)]
      (.setNeutralButton builder neutral-name nil))
    (when-let [negative-name (:negative-name buttons)]
      (.setNegativeButton builder negative-name nil))
    (.setMessage builder message)
    (.setView builder view)
    (let [^AlertDialog dialog (.create builder)
          btn-action (fn [dialog button func]
                       (proxy [android.view.View$OnClickListener] []
                         (onClick [v]
                           (when (func context view button)
                             (try (.dismiss dialog)
                               (catch Exception e nil))))))]
      (.setOnShowListener
        dialog
        (proxy [android.content.DialogInterface$OnShowListener] []
          (onShow [d]
            (when-let [positive-btn (.getButton d AlertDialog/BUTTON_POSITIVE)]
              (->> (btn-action d positive-btn (:positive-func buttons))
                   (.setOnClickListener positive-btn)))
            (when-let [neutral-btn (.getButton d AlertDialog/BUTTON_NEUTRAL)]
              (->> (btn-action d neutral-btn (:neutral-func buttons))
                   (.setOnClickListener neutral-btn)))
            (when-let [negative-btn (.getButton d AlertDialog/BUTTON_NEGATIVE)]
              (->> (btn-action d negative-btn (:negative-func buttons))
                   (.setOnClickListener negative-btn))))))
      (.setCanceledOnTouchOutside dialog false)
      dialog)))

(defn show-dialog!
  ([^Activity context ^String title ^String message]
   (let [builder (android.app.AlertDialog$Builder. context)]
     (.setPositiveButton builder (r/get-string :ok) nil)
     (let [^AlertDialog dialog (.create builder)]
       (.setTitle dialog title)
       (.setMessage dialog message)
       (.setCanceledOnTouchOutside dialog false)
       (try (.show dialog)
         (catch Exception e nil)))))
  ([^Activity context ^String message ^View view buttons]
   (-> (proxy [DialogFragment] []
         (onCreate [bundle]
           (proxy-super onCreate bundle)
           (.setRetainInstance ^DialogFragment this true))
         (onDetach []
           (proxy-super onDetach)
           (when view (.removeView (.getParent view) view)))
         (onDestroyView []
           (when (and (.getDialog ^DialogFragment this)
                      (.getRetainInstance ^DialogFragment this))
             (.setDismissMessage (.getDialog ^DialogFragment this) nil))
           (proxy-super onDestroyView))
         (onCreateDialog [bundle]
           (proxy-super onCreateDialog bundle)
           (create-dialog context message view buttons)))
       (.show (.getFragmentManager context) "dialog")
       (try (catch Exception e nil)))))

(defn show-pending-user-dialog!
  [^Activity context]
  (show-dialog! context nil (r/get-string :pending_user)))

(defn show-welcome-dialog!
  [^Activity context]
  (show-dialog! context
                nil
                (views-dialog/get-welcome-view context)
                {:positive-name (r/get-string :ok)
                 :positive-func actions/cancel}))

(defn show-new-user-dialog!
  [^Activity context content]
  (show-dialog! context
                (r/get-string :found_user)
                nil
                {:positive-name (r/get-string :download_user)
                 :positive-func
                 (fn [context dialog-view button-view]
                   (actions/toggle-fav! context content true))
                 :negative-name (r/get-string :cancel)
                 :negative-func
                 (fn [context dialog-view button-view]
                   (.finish context))}))

(defn show-delete-post-dialog!
  [^Activity context ^View dialog-view ^Button button-view create-time]
  (show-dialog! context
                (r/get-string :confirm_delete)
                nil
                {:positive-name (r/get-string :delete)
                 :positive-func
                 (fn [c d b]
                   (let [text-view (.findViewWithTag dialog-view "post-body")]
                     (.setText text-view "")
                     (actions/new-post! context
                                        dialog-view
                                        button-view
                                        create-time
                                        nil
                                        0)))
                 :negative-name (r/get-string :cancel)
                 :negative-func actions/cancel})
  false)

(defn show-delete-user-dialog!
  [^Activity context user-hash]
  (show-dialog! context
                (r/get-string :confirm_delete)
                nil
                {:positive-name (r/get-string :delete)
                 :positive-func (fn [c d b]
                                  (users/delete-user! user-hash)
                                  (.finish context)
                                  (actions/show-home! context {}))
                 :negative-name (r/get-string :cancel)
                 :negative-func actions/cancel}))

(defn show-export-dialog!
  [^Activity context ^View dialog-view ^Button button-view]
  (let [view (ui/make-ui context [:edit-text {:single-line true
                                              :layout-width :fill
                                              :hint (r/get-string :password)}])
        input-type (bit-or InputType/TYPE_CLASS_TEXT
                           InputType/TYPE_TEXT_VARIATION_PASSWORD)]
    (.setInputType view input-type)
    (show-dialog! context
                  (r/get-string :export_desc)
                  view
                  {:positive-name (r/get-string :save)
                   :positive-func
                   (fn [c d b]
                     (actions/zip-and-send! context (.toString (.getText view)))
                     true)
                   :negative-name (r/get-string :cancel)
                   :negative-func actions/cancel}))
  true)

(defn show-import-dialog!
  [^Activity context ^String uri-str]
  (let [view (ui/make-ui context [:edit-text {:single-line true
                                              :layout-width :fill
                                              :hint (r/get-string :password)}])
        input-type (bit-or InputType/TYPE_CLASS_TEXT
                           InputType/TYPE_TEXT_VARIATION_PASSWORD)]
    (.setInputType view input-type)
    (show-dialog! context
                  (r/get-string :import_desc)
                  view
                  {:positive-name (r/get-string :import_user)
                   :positive-func
                   (fn [c d b]
                     (actions/unzip-and-save!
                       context (.toString (.getText view)) uri-str)
                     true)
                   :negative-name (r/get-string :cancel)
                   :negative-func actions/cancel})))

(defn show-switch-user-dialog!
  [^Activity context content]
  (let [view (ui/make-ui context [:scroll-view {}
                                  [:linear-layout {:orientation 1}]])]
    ; add each user to the list
    (future
      (let [linear-layout (.getChildAt view 0)
            items (for [user-hash (io/read-user-list-file)]
                    (db/get-single-user-data {:userhash user-hash}))]
        (doseq [item items]
          (let [title (if (= 0 (count (:title item)))
                        (r/get-string :no_name)
                        (:title item))
                list-item (ui/make-ui context
                                      [:linear-layout {:orientation 0}
                                       [:button {:text title
                                                 :layout-weight 3}]
                                       [:button {:text (r/get-string :delete)
                                                 :layout-weight 1}]])
                select-button (.getChildAt list-item 0)
                delete-button (.getChildAt list-item 1)]
            (thread/on-ui
              (.setEnabled select-button (not (c/is-me? (:userhash item))))
              (.setOnClickListener
                select-button
                (proxy [android.view.View$OnClickListener] []
                  (onClick [v]
                    (users/load-user! (:userhash item))
                    (.finish context)
                    (actions/show-home! context {}))))
              (.setOnClickListener
                delete-button
                (proxy [android.view.View$OnClickListener] []
                  (onClick [v]
                    (show-delete-user-dialog! context (:userhash item)))))
              (.addView linear-layout list-item))))))
    ; display a dialog with the list
    (show-dialog! context
                  nil
                  view
                  {:positive-name (r/get-string :create_user)
                   :positive-func (fn [context dialog-view button-view]
                                    (users/load-user! (users/create-user!))
                                    (a/fav-default-user!)
                                    (.finish context)
                                    (actions/show-home! context {}))
                   :negative-name (r/get-string :cancel)
                   :negative-func actions/cancel})))

(defn show-confirm-dialog!
  [^Activity context content func]
  (show-dialog! context
                (utils/get-string-at-runtime context (:confirm content))
                nil
                {:positive-name (r/get-string :ok)
                 :positive-func
                 (fn [context dialog-view button-view]
                   (func context content true))
                 :negative-name (r/get-string :cancel)
                 :negative-func actions/cancel}))

(defn show-new-post-dialog!
  [^Activity context content]
  (actions/clear-attachments! context)
  (let [view (views-dialog/get-new-post-view context content)]
    (show-dialog! context
                  nil
                  view
                  {:positive-name (if (:ptrtime (.getTag view))
                                    (r/get-string :send_reply)
                                    (r/get-string :send))
                   :positive-func actions/new-post!
                   :neutral-name (r/get-string :attach_pics)
                   :neutral-func actions/attach-to-post!
                   :negative-name (r/get-string :cancel)
                   :negative-func actions/cancel})))

(defn show-edit-post-dialog!
  [^Activity context content]
  (let [post (:post content)
        pics (db/get-pic-data post (:time post) false)]
    (actions/clear-attachments! context)
    (show-dialog! context
                  nil
                  (views-dialog/get-new-post-view context content)
                  {:positive-name (r/get-string :save)
                   :positive-func
                   (fn [context dialog-view button-view]
                     (actions/new-post! context
                                        dialog-view
                                        button-view
                                        (:time post)
                                        (for [pic pics] (:pichash pic))
                                        1))
                   :neutral-name (r/get-string :delete)
                   :neutral-func
                   (fn [context dialog-view button-view]
                     (show-delete-post-dialog! context
                                               dialog-view
                                               button-view
                                               (:time post)))
                   :negative-name (r/get-string :cancel)
                   :negative-func actions/cancel})))

(defn show-profile-dialog!
  [^Activity context content]
  (show-dialog! context
                nil
                (views-dialog/get-profile-view context content)
                (if (c/is-me? (:userhash content))
                  {:positive-name (r/get-string :save)
                   :positive-func actions/save-profile!
                   :neutral-name (r/get-string :export_start)
                   :neutral-func show-export-dialog!
                   :negative-name (r/get-string :cancel)
                   :negative-func actions/cancel}
                  {:positive-name (r/get-string :ok)
                   :positive-func actions/cancel})))
