(ns slopp.render
  "VFS render: project a namespace's current source from the store on demand
  (C1/C6). Lossless — concatenating each element's CST string reproduces the
  ingested source exactly. This is what tools/agents 'read'; nothing is written
  to disk unless an explicit build asks."
  (:require [rewrite-clj.node :as n]
            [slopp.store :as store]))

(defn render-ns
  "Render `ns-sym`'s current source as a string from the store."
  [store ns-sym]
  (apply str (map (comp n/string :node) (store/elements store ns-sym))))
