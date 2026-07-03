(ns slopp.turn
  "One-shot turn markers for Claude Code hooks: append a :turn-begin /
  :turn-end delta DIRECTLY to a project's journal, out-of-band — the agent's
  own server absorbs it via journal sync (m5b). This is how the VERBATIM
  user prompt reaches provenance without the model relaying it:

    UserPromptSubmit hook:  clojure -M -m slopp.turn <dir> begin <agent> <prompt...>
    Stop hook:              clojure -M -m slopp.turn <dir> end <agent>"
  (:require [slopp.db :as db]
            [slopp.store :as store]))

(defn -main [dir kind agent & intent-words]
  (let [conn (db/open! dir)]
    (try
      (loop [n 0]
        (let [st (or (db/load-store conn) (store/empty-store))
              [st' _] (store/record-turn st
                                         (if (= kind "end") :turn-end :turn-begin)
                                         :agent agent
                                         :intent (when (and (seq intent-words)
                                                            (not= kind "end"))
                                                   (clojure.string/join " " intent-words)))
              head (:id (last (store/deltas st)))]
          (if (db/append! conn st'
                          (drop (count (store/deltas st)) (store/deltas st'))
                          [] head)
            (println "turn" kind "recorded for" agent)
            (if (< n 10)
              (recur (inc n))
              (binding [*out* *err*] (println "contention — giving up"))))))
      (finally (.close ^java.sql.Connection conn)))))
