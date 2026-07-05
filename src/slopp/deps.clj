(ns slopp.deps
  "External dependency ANALYSIS (Tier 1, P4-deps M4): resolve a dependency's
  own jars and extract its API SURFACE (provided namespaces + per-var arities,
  docstrings, macro flags) via clj-kondo — the metadata that lets slopp know
  what a dep exposes without reading jars, and lets the effect boundary (M3)
  tell an external call from a store/stdlib one. Content-addressed: a surface
  is a pure function of `coord@version`, so it's computed once and cached
  (`db/dep_surface`)."
  (:require [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clj-kondo.core :as kondo]
            [slopp.repl :as repl])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- neutral-dir
  "A scratch cwd with no deps.edn, so `-Spath` resolves ONLY clojure + the
  requested deps (independent of wherever slopp is running)."
  []
  (str (Files/createTempDirectory "slopp-deps-cp" (make-array FileAttribute 0))))

(defn- spath
  "The resolved classpath entries for `deps` (a lib→coord map), as a set."
  [deps dir]
  (let [r (sh/sh repl/clojure-bin "-Spath" "-Sdeps" (pr-str {:deps deps})
                 :dir dir)]
    (if (zero? (:exit r))
      (set (remove str/blank? (str/split (str/trim (:out r)) #":")))
      (throw (ex-info (str "classpath resolution failed: " (:err r)) {})))))

(defn dep-jars
  "The classpath entries contributed by `lib`@`coord` ALONE — its own jar plus
  any transitives, minus the clojure baseline (a classpath diff), so the
  surface is exactly this dependency's contribution. Returns a vector of paths."
  [lib coord]
  (let [dir (neutral-dir)]
    (vec (set/difference (spath {lib coord} dir) (spath {} dir)))))

(defn surface
  "Analyze `jars` (from `dep-jars`) into an API surface:
  {:namespaces #{ns…} :vars {ns/name {:arities :varargs-min :doc :macro? :private?}}}.
  clj-kondo over the jars (source-fed — Clojure libs ship source); public vars
  only in `:vars` (private/impl vars still count toward `:namespaces`)."
  [jars]
  (if (empty? jars)
    {:namespaces #{} :vars {}}
    (let [an (:analysis (kondo/run! {:lint jars
                                     :config {:output {:analysis true}}}))]
      {:namespaces (into (sorted-set) (map :name) (:namespace-definitions an))
       :vars (into {}
                   (comp (remove :private)
                         (map (fn [d]
                                [(symbol (str (:ns d)) (str (:name d)))
                                 (cond-> {}
                                   (:fixed-arities d)     (assoc :arities (vec (sort (:fixed-arities d))))
                                   (:varargs-min-arity d) (assoc :varargs-min (:varargs-min-arity d))
                                   (:doc d)               (assoc :doc (first (str/split-lines (:doc d))))
                                   (:macro d)             (assoc :macro? true))])))
                   (:var-definitions an))})))

(defn coord-key
  "The content-address of a dependency declaration: \"lib@version\" (or the
  full coord for non-mvn coords). The cache/memo key for surface + native
  verdict."
  [lib coord]
  (str lib "@" (or (:mvn/version coord)
                   (:git/sha coord)
                   (:local/root coord)
                   (pr-str coord))))

(def ^:private surface-cache
  "Process-level memo, coord-key → surface — content-addressed, so identical
  across stores and runs (Unison-style). Complements the db `dep_surface`
  cache (which survives restart)."
  (atom {}))

(defn surface-of
  "Full surface for `lib`@`coord` (resolve jars, then analyze), memoized in
  process by `coord@version`. The slow path runs once per coord."
  [lib coord]
  (let [k (coord-key lib coord)]
    (or (@surface-cache k)
        (let [s (surface (dep-jars lib coord))]
          (swap! surface-cache assoc k s)
          s))))
