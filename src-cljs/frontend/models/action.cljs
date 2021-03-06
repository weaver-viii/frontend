(ns frontend.models.action
  (:require [clojure.string :as string]
            [frontend.datetime :as datetime]
            [frontend.models.feature :as feature]
            [frontend.models.project :as proj]
            [frontend.utils :as utils :include-macros true]
            [goog.string :as gstring]
            goog.string.format))

(defn failed? [action]
  (#{"failed" "timedout" "canceled" "infrastructure_fail"} (:status action)))

(defn has-content? [action]
  (or (:has_output action)
      (:bash_command action)
      (:output action)))

(defn visible? [action]
  (get action :show-output (or (not= "success" (:status action))
                               (seq (:messages action)))))

(defn running? [action]
  (and (:start_time action)
       (not (:stop_time action))
       (not (:run_time_millis action))))

(defn duration [{:keys [start_time stop_time] :as action}]
  (cond (:run_time_millis action) (datetime/as-duration (:run_time_millis action))
        (:start_time action) (datetime/as-duration (- (.getTime (js/Date.))
                                                      (js/Date.parse start_time)))
        :else nil))

(defn new-converter [action converter-path]
  (let [default-color "white"
        starting-state (clj->js (or (get-in action converter-path)
                                    {:color "white" :italic false :bold false}))]
    (js/CI.terminal.ansiToHtmlConverter default-color "brblack" starting-state)))

(defn strip-console-codes
  "Strips console codes if output is over 2mb (assuming 2 bytes per char)"
  [message]
  (when message
    (string/replace message #"\u001B\[[^A-Za-z]*[A-Za-z]" "")))

(defn format-output [action output-index]
  "Html-escape the output, and then either:
   1. skip colorization because it was truncated + stripped by the backend.
   2. strip the console codes when there are too many because the browser
      can't handle crazy large numbers of spans.
   3. colorize the output."
  (let [output (get-in action [:output output-index])
        converter-path [:converters-state (keyword (:type output))]
        html-escaped-message (-> output :message gstring/htmlEscape)
        stripped-message (strip-console-codes html-escaped-message)
        converter (new-converter action converter-path)
        ;; This is a rough proxy for "if we put this string into our ansiToHtmlConverter
        ;; it will create so many spans the browser will fall over"
        ;; we should evetnually use a better proxy (or a better ansiToHtmlConverter)
        max-escape-code-characters 1000

        [converted-message converter-state] (cond
                                              (:truncated-client-side? action)
                                              [html-escaped-message nil]

                                              (< max-escape-code-characters
                                                 (- (count stripped-message)
                                                    (count html-escaped-message)))
                                              [stripped-message nil]

                                              :else    ; colorize it
                                              [(str (.append converter html-escaped-message)
                                                    (.get_trailing converter))
                                               (utils/js->clj-kw (.currentState converter))])]
    (-> action
        (assoc-in [:output output-index :converted-message] converted-message)
        (assoc-in [:output output-index :react-key] (utils/uuid))
        ;; set the converter state to nil if unused,
        ;; so that the next converter reverts to defaults
        (assoc-in converter-path converter-state))))

(defn format-latest-output [action]
  (if-let [output (seq (:output action))]
    (format-output action (dec (count output)))
    action))

(defn format-all-output [action]
  (if-let [output (seq (:output action))]
    (reduce format-output action (range (count output)))
    action))

(def max-output-size
  "Limits the number of chacaters we will try to display in the dom.
   The full output is availible for download."
  (* 400 1000))
