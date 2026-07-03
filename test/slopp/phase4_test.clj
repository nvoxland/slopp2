(ns slopp.phase4-test
  "Phase 4 m1: many agents, ONE store/image. Native MCP over streamable HTTP
  (POST /mcp) + per-agent attribution on every delta."
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [slopp.api :as api]
            [slopp.http :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(defn- rpc! [port body]
  (let [client (HttpClient/newHttpClient)
        req (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/mcp")))
                (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (when (seq (.body resp)) (json/parse-string (.body resp) true))}))

(defn- tool! [port agent-name tool args]
  (get-in (rpc! port {:jsonrpc "2.0" :id 1 :method "tools/call"
                      :params {:name tool
                               :arguments (assoc args :agent agent-name)}})
          [:body :result :content 0 :text]))

(deftest mcp-over-http-speaks-the-protocol
  (let [port 7411
        srv  (http/start-server! port {})]
    (try
      (testing "initialize / tools list / notifications, per JSON-RPC"
        (let [init (rpc! port {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})]
          (is (= 200 (:status init)))
          (is (= "slopp" (get-in init [:body :result :serverInfo :name]))))
        (is (= 202 (:status (rpc! port {:jsonrpc "2.0"
                                        :method "notifications/initialized"}))))
        (let [tools (rpc! port {:jsonrpc "2.0" :id 2 :method "tools/list"})]
          (is (some #(= "edit_replace_form" (:name %))
                    (get-in tools [:body :result :tools])))))
      (testing "tool calls work end-to-end through /mcp"
        (is (re-find #":forms 2"
                     (tool! port "alice" "ingest"
                            {:ns "m1.core"
                             :source "(ns m1.core)\n(defn f [x] (* 2 x))\n"})))
        (is (re-find #"\b10\b" (tool! port "alice" "query_eval"
                                      {:code "(m1.core/f 5)"}))))
      (finally (http/stop-server! srv)))))

(deftest two-agents-one-store
  (let [port 7412
        srv  (http/start-server! port {})]
    (try
      (tool! port "alice" "ingest"
             {:ns "team.core"
              :source "(ns team.core)\n(defn a [x] x)\n(defn b [x] x)\n"})
      (testing "concurrent different-form writes from two agents both land"
        (let [results (doall
                       (pmap (fn [[agent nm body]]
                               (tool! port agent "edit_replace_form"
                                      {:ns "team.core" :name nm :source body
                                       :prompt (str agent "'s change")}))
                             [["alice" "a" "(defn a [x] (+ x 1))"]
                              ["bob"   "b" "(defn b [x] (+ x 2))"]]))]
          (is (every? #(not (re-find #":error|:conflict" %)) results))
          (let [src (tool! port "carol" "query_source" {:ns "team.core"})]
            (is (re-find #"\(\+ x 1\)" src))
            (is (re-find #"\(\+ x 2\)" src)))))
      (testing "history tells WHO did WHAT (per-agent provenance)"
        (let [hist (tool! port "carol" "query_history" {:ns "team.core"})]
          (is (re-find #":agent \"alice\"" hist))
          (is (re-find #":agent \"bob\"" hist))))
      (finally (http/stop-server! srv)))))

(deftest attribution-flows-through-every-write-kind
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'at.core "(ns at.core)\n(defn f [x] x)\n" :agent "ingester")
      (api/edit-replace! sess 'at.core 'f "(defn f [x] (inc x))"
                         :prompt "bump" :agent "replacer")
      (api/add-form! sess 'at.core "(defn g [x] (f x))" :agent "adder")
      (api/rename! sess 'at.core 'g 'h :agent "renamer")
      (let [by-op (into {} (map (juxt :op :agent)) (slopp.store/deltas (:store @sess)))]
        (is (= "ingester" (by-op :ingest)))
        (is (= "replacer" (by-op :replace)))
        (is (= "adder"    (by-op :add)))
        (is (= "renamer"  (by-op :rename))))
      (finally (api/close! sess)))))
