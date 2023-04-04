(ns rems.css.styles
  "CSS stylesheets are generated by garden automatically when
  accessing the application on a browser. The garden styles can also
  be manually compiled by calling the function `rems.css.styles/screen-css`.

  For development purposes with live reload, the styles are rendered to
  `target/resources/public/css/:language/screen.css` whenever this ns is evaluated
  so that we can autoreload them."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET defroutes]]
            [garden.color :as c]
            [garden.core :as g]
            [garden.def :refer [defkeyframes]]
            [garden.selectors :as s]
            [garden.stylesheet :as stylesheet]
            [garden.units :as u]
            [medley.core :refer [map-vals remove-vals]]
            [mount.core :as mount]
            [rems.config :refer [env]]
            [rems.context :as context]
            [rems.css.style-utils :refer [theme-getx get-logo-image get-navbar-logo get-logo-name-sm]]
            [ring.util.response :as response]))

(def content-max-width (u/px 2560))
(def application-page-max-width (u/px 1800))
(def navbar-max-width (u/px 1200))
(def logo-height-menu (u/px 40))
(def logo-height (u/px 150))
(def menu-height 56)
(def bootstrap-media-breakpoints {:xs (u/px 576)
                                  :sm (u/px 768)
                                  :md (u/px 992)
                                  :lg (u/px 1200)
                                  :xl (u/px 1600)})

(defn- form-placeholder-styles []
  (list
   [".form-control::placeholder" {:color "#555"}] ; Standard
   [".form-control::-webkit-input-placeholder" {:color "#555"}] ; WebKit, Blink, Edge
   [".form-control:-moz-placeholder" {:color "#555"
                                      :opacity 1}] ; Mozilla Firefox 4 to 18
   [".form-control::-moz-placeholder" {:color "#555"
                                       :opacity 1}] ; Mozilla Firefox 19+
   [".form-control:-ms-input-placeholder" {:color "#555"}])) ; Internet Explorer 10-11

(defn- media-queries []
  (list
   (stylesheet/at-media {:max-width (:xs bootstrap-media-breakpoints)}
                        [(s/descendant :.rems-table.cart :tr)
                         {:border-bottom :none}])
   (stylesheet/at-media {:max-width (:md bootstrap-media-breakpoints)}
                        [:div.commands.flex-nowrap {:flex-wrap "wrap !important"}]) ; wrap table commands
   (stylesheet/at-media {:max-width (:xl bootstrap-media-breakpoints)}
                        [:.lg-fs70pct {:font-size (u/percent 70)}])
   (stylesheet/at-media {:max-width (u/px 870)}
                        [:.user-widget [:.icon-description {:display "none"}]])
   (stylesheet/at-media {:prefers-reduced-motion :reduce}
                        [:body {:scroll-behavior :auto}])))

(defn- logo-styles []
  (list
   [:.logo-menu {:height logo-height-menu
                 :background-color (theme-getx :logo-bgcolor)
                 :width "100px"
                 :padding-top "0px"
                 :padding-bottom "0px"}]
   [:.logo {:height logo-height
            :background-color (theme-getx :logo-bgcolor)
            :width "100%"
            :margin "0 auto"
            :padding "0 20px"
            :margin-bottom (u/em 1)}]
   [(s/descendant :.logo :.img)
    (s/descendant :.logo-menu :.img)
    {:height "100%"
     :background-color (theme-getx :logo-bgcolor)
     :-webkit-background-size :contain
     :-moz-o-background-size :contain
     :-o-background-size :contain
     :background-size :contain
     :background-repeat :no-repeat
     :background-position [[:center :center]]
     :background-origin (theme-getx :logo-content-origin)}]
   [(s/descendant :.logo :.img) {:background-image (get-logo-image context/*lang*)}]
   [(s/descendant :.logo-menu :.img) {:background-image (get-navbar-logo context/*lang*)}]
   (stylesheet/at-media {:max-width (:sm bootstrap-media-breakpoints)}
                        (list
                         [(s/descendant :.logo :.img)
                          {:background-color (theme-getx :logo-bgcolor)
                           :background-image (get-logo-name-sm context/*lang*)
                           :-webkit-background-size :contain
                           :-moz-background-size :contain
                           :-o-background-size :contain
                           :background-size :contain
                           :background-repeat :no-repeat
                           :background-position [[:center :center]]}]
                         [:.logo {:height logo-height}]
                         [:.logo-menu {:display "none"}]))))

(defn- phase-styles []
  [:.phases {:width "100%"
             :height (u/px 40)
             :display "flex"
             :flex-direction "row"
             :justify-content "stretch"
             :align-items "center"}
   [:.phase {:background-color (theme-getx :phase-bgcolor)
             :color (theme-getx :phase-color)
             :flex-grow 1
             :height (u/px 40)
             :display "flex"
             :flex-direction "row"
             :justify-content "stretch"
             :align-items "center"}
    [:span {:flex-grow 1
            :text-align "center"
            :min-width (u/px 100)}]
    [(s/& ":not(:last-of-type):after") {:content "\"\""
                                        :border-top [[(u/px 20) :solid :white]]
                                        :border-left [[(u/px 10) :solid :transparent]]
                                        :border-bottom [[(u/px 20) :solid :white]]
                                        :border-right "none"}]
    [(s/& ":first-of-type") {:border-top-left-radius (u/px 4)
                             :border-bottom-left-radius (u/px 4)}]
    [(s/& ":last-of-type") {:border-top-right-radius (u/px 4)
                            :border-bottom-right-radius (u/px 4)}]
    [(s/& ":not(:first-of-type):before") {:content "\"\""
                                          :border-top [[(u/px 20) :solid :transparent]]
                                          :border-left [[(u/px 10) :solid :white]]
                                          :border-bottom [[(u/px 20) :solid :transparent]]
                                          :border-right "none"}]
    [:&.active {:color (theme-getx :phase-color-active)
                :background-color (theme-getx :phase-bgcolor-active)
                :border-color (theme-getx :phase-bgcolor-active)}]
    [:&.completed {:color (theme-getx :phase-color-completed)
                   :background-color (theme-getx :phase-bgcolor-completed)
                   :border-color (theme-getx :phase-bgcolor-completed)}]]])

(defn- actions-float-menu
  "The #actions floating menu can be too long for some screens. There is no clean solution for this in pure CSS
  and to avoid yet another random JS-library we make the element scrollable with a dynamic max-height. This can
  break if the 105px space is not enough anymore but works for now.

  We also do not want these styles to affect the mobile layout (i.e. more narrow than 992) where the actions
  is at the bottom of everything and has any height available."
  []
  (list
   (stylesheet/at-media {:min-width (u/px 992)
                         :max-height (u/px 1080)}
                        [:#actions ; make buttons here smaller on small screens
                         [:.btn {:font-size "0.875rem" ; duplicates bootstrap style btn-sm
                                 :padding "0.25rem 0.5rem"
                                 :line-height 1.5
                                 :border-radius "0.2rem"}]])
   (stylesheet/at-media {:min-width (u/px 992)}
                        [:#actions {:overflow-x :hidden
                                    :overflow-y :auto
                                    :max-height "calc(100vh - 105px)"}])))

;; TODO inline me
(defn- button-navbar-font-weight []
  ;; Default font-weight to 700 so the text is considered
  ;; 'large text' and thus requires smaller contrast ratio for
  ;; accessibility.
  (theme-getx :button-navbar-font-weight))

(defn table-selection-bgcolor []
  (if-let [selection-bgcolor (theme-getx :table-selection-bgcolor)]
    selection-bgcolor
    (-> (theme-getx :table-hover-bgcolor :table-bgcolor :color3)
        (c/darken 15))))

(defn- rems-table-styles []
  (list
   [:.rems-table.cart {:background "#fff"
                       :color "#000"
                       :margin 0}
    [".cart-bundle:not(:last-child)" {:border-bottom [[(u/px 1) :solid (theme-getx :color1)]]}]
    [:td:before {:content "initial"}]
    [:th
     :td:before
     {:color "#000"}]
    [:tr
     [(s/& (s/nth-child "2n")) {:background "#fff"}]]]
   [:.table-border {:padding 0
                    :margin "1rem 0"
                    :border (theme-getx :table-border)
                    :border-radius (u/rem 0.4)}]
   [:.rems-table {:min-width "100%"
                  :word-break :break-word
                  :background-color (theme-getx :table-bgcolor :color1)
                  :box-shadow (theme-getx :table-shadow)
                  :color (theme-getx :table-text-color)}
    [:th {:white-space :nowrap
          :color (theme-getx :table-heading-color)
          :background-color (theme-getx :table-heading-bgcolor :color3)}]
    [:th
     :td {:text-align "left"
          :padding "0.5rem 1rem"}]
    [:.selection {:width (u/rem 2)
                  :padding-right 0}]
    [:td:before
     {:color (theme-getx :table-text-color)}]
    [:tr
     [:&:hover {:color (theme-getx :table-hover-color :table-text-color)
                :background-color (theme-getx :table-hover-bgcolor :color2)}]
     [:&.selected {:background-color (theme-getx :table-selection-bgcolor (table-selection-bgcolor))}]
     (when (theme-getx :table-stripe-color nil)
       [(s/& (s/nth-child "2n"))
        [:&:hover {:color (theme-getx :table-hover-color :table-text-color)
                   :background-color (theme-getx :table-hover-bgcolor :color2)}]
        {:background-color (theme-getx :table-stripe-color :table-bgcolor)}
        [:&.selected {:background-color (theme-getx :table-selection-bgcolor (table-selection-bgcolor))}]])]

    (for [i (range 10)]
      [(str ".bg-depth-" i) {:background-color (str "rgba(0,0,0," (/ i 30.0) ")")}])
    (for [i (range 10)]
      [(str ".fs-depth-" i) {:font-size (str (format "%.2f" (+ 0.75 (Math/pow 2 (- i)))) "rem")}])
    (for [i (range 10)]
      [(str ".pad-depth-" i) {:padding-left (u/rem (* 1.8 i))}])]

   [:.rems-table.cart {:box-shadow :none}]
   [:.inner-cart {:margin [[(u/rem 1) 0]]}]
   [:.outer-cart {:margin [[(u/rem 1) 0]]
                  :border [[(u/px 1) :solid (theme-getx :color1)]]
                  :border-radius (u/rem 0.4)}]
   [:.cart-title {:margin (u/rem 1)}]
   [:.fa-shopping-cart {:margin-right (u/em 0.5)}]
   [:.cart-item {:padding-right (u/em 1)}
    [:>span {:display :inline-block :vertical-align :middle}]]
   ;; TODO: Change naming of :color3? It is used as text color here,
   ;;   which means that it should have a good contrast with light background.
   ;;   This could be made explicit by changing the name accordingly.
   [:.text-highlight {:color (theme-getx :color3)
                      :font-weight "bold"}]))

(defn- form-group []
  {:position "relative"
   :border-radius (u/rem 0.4)
   :padding (u/px 10)
   :margin-top 0
   :margin-bottom (u/px 16)})

(defn- dashed-form-group []
  (assoc (form-group)
         :border "2px dashed #ccc"))

(defn- solid-form-group []
  (assoc (form-group)
         :padding (u/rem 1)
         :border "2px solid #eee"))

(defn- remove-nil-vals
  "Recursively removes all keys with nil values from a map."
  [obj]
  (assert (not= "" obj))
  (cond
    (record? obj) obj
    (map? obj) (->> obj
                    (map-vals remove-nil-vals)
                    (remove-vals nil?)
                    not-empty)
    (vector? obj) (mapv remove-nil-vals obj)
    (seq? obj) (map remove-nil-vals obj)
    :else obj))

(defn- make-important [style]
  (map-vals #(str % " !important") style))

(deftest test-remove-nil-vals
  (testing "empty"
    (is (= nil
           (remove-nil-vals {}))))
  (testing "flat"
    (is (= nil
           (remove-nil-vals {:a nil})))
    (is (= {:a 1}
           (remove-nil-vals {:a 1})))
    (is (= {:a false}
           (remove-nil-vals {:a false})))
    (is (= {:a "#fff"}
           (remove-nil-vals {:a "#fff"}))))
  (testing "nested"
    (is (= nil
           (remove-nil-vals {:a {:b nil}})))
    (is (= {:a {:b 1}}
           (remove-nil-vals {:a {:b 1}}))))
  (testing "multiple keys"
    (is (= {:b 2}
           (remove-nil-vals {:a nil
                             :b 2}))))
  (testing "vectors"
    (is (vector? (remove-nil-vals [1])))
    (is (= []
           (remove-nil-vals [])))
    (is (= [:a]
           (remove-nil-vals [:a])))
    (is (= [:a nil]
           (remove-nil-vals [:a {}])))
    (is (= [:a {:b 1}]
           (remove-nil-vals [:a {:b 1}])))
    (is (= [:a nil]
           (remove-nil-vals [:a {:b nil}]))))
  (testing "lists"
    (is (seq? (remove-nil-vals '(1))))
    (is (= '()
           (remove-nil-vals '())))
    (is (= '(:a)
           (remove-nil-vals '(:a))))
    (is (= '(:a nil)
           (remove-nil-vals '(:a {})))))
  (testing "records"
    (is (= (u/px 10)
           (remove-nil-vals (u/px 10)))))
  (testing "empty strings are not supported"
    ;; CSS should not contain empty strings, but to be sure
    ;; that we don't accidentally break stuff, we don't convert
    ;; them to nil but instead throw an error.
    (is (thrown? AssertionError (remove-nil-vals {:a ""})))))

(defkeyframes shake
  ["10%, 90%"
   {:transform "perspective(500px) translate3d(0, 0, 1px)"}]
  ["20%, 80%"
   {:transform "perspective(500px) translate3d(0, 0, -3px)"}]
  ["30%, 50%, 70%"
   {:transform "perspective(500px) translate3d(0, 0, 8px)"}]
  ["40%, 60%"
   {:transform "perspective(500px) translate3d(0, 0, -8px)"}])

(defkeyframes pulse-opacity
  ["0%"
   {:opacity "1.0"}]
  ["100%"
   {:opacity "0.0"}])

(defn build-screen []
  (list
   [:* {:margin 0}]
   [:p:last-child {:margin-bottom 0}]
   [:a
    :button
    {:cursor :pointer
     :color (theme-getx :link-color)}
    [:&:hover {:color (theme-getx :link-hover-color :color4)}]]
   [:.pointer {:cursor :pointer}
    [:label.form-check-label {:cursor :pointer}]]
   [:html {:position :relative
           :min-width (u/px 320)
           :height (u/percent 100)}]
   [:body {:font-family (theme-getx :font-family)
           :min-height (u/percent 100)
           :display :flex
           :flex-direction :column
           :padding-top (u/px (+ menu-height 12))
           :scroll-behavior :smooth}]
   [:h1 :h2 {:font-weight 400}]
   [:h1 {:margin-bottom (u/rem 2)
         :margin-top (u/rem 2)}]
   [:#app {:min-height (u/percent 100)
           :flex 1
           :display :flex}]
   [(s/> :#app :div) {:min-height (u/percent 100)
                      :flex 1
                      :display :flex
                      :flex-direction :column}]
   [:.fixed-top {:background-color "#fff"
                 :border-bottom (theme-getx :header-border)
                 :box-shadow (theme-getx :header-shadow :table-shadow)
                 :min-height menu-height}]
   [:.skip-navigation {:position :absolute
                       :left (u/em -1000)}
    [:&:active
     :&:focus
     {:left (u/em 0)}]]
   ["a:not(.nav-link):not(.btn)" {:text-decoration :underline}]
   [:#main-content {:display :flex
                    :flex-direction :column
                    :flex-wrap :nowrap
                    :min-height (u/px 300)
                    :max-width content-max-width
                    :align-items :center
                    ;; Height of navigation + logo, to avoid page content going under
                    ;; the navigation bar when the main content is focused.
                    ;; See https://stackoverflow.com/questions/4086107/fixed-page-header-overlaps-in-page-anchors
                    :padding-top (u/px 212)
                    :margin-top (u/px -212)
                    :margin-bottom (u/rem 1)}
    ["&>*" {:min-width (u/px 512)}]]
   [:#empty-space {:flex-grow 1}]
   [:#main-content.page-actions {:max-width (u/percent 100)}]
   [(s/> :.spaced-sections "*:not(:first-child)") {:margin-top (u/rem 1)}]
   [:.btn {:white-space :nowrap
           :font-weight (button-navbar-font-weight)}]
   ;; override Bootstrap blue active color with its hover color
   [".dropdown-item.active"
    ".dropdown-item:active"
    {:background-color "#e9ecef"}]
   ;; Bootstrap has inaccessible focus indicators in particular
   ;; for .btn-link and .btn-secondary, so we define our own.
   [:a:focus :button:focus :.btn.focus :.btn:focus
    "h1[tabindex]:focus-within"
    {:outline 0
     :box-shadow "0 0 0 0.2rem rgba(38,143,255,.5) !important"}]
   [:.btn-primary
    [:&:hover
     :&:focus
     :&:active:hover
     "&:not(:disabled):not(.disabled):active"
     {:background-color (theme-getx :primary-button-hover-bgcolor :primary-button-bgcolor :color4)
      :border-color (theme-getx :primary-button-hover-bgcolor :primary-button-bgcolor :color4)
      :color (theme-getx :primary-button-hover-color :primary-button-color)
      :outline-color :transparent}]
    {:background-color (theme-getx :primary-button-bgcolor :color4)
     :border-color (theme-getx :primary-button-bgcolor :color4)
     :color (theme-getx :primary-button-color)
     :outline-color :transparent}]
   [:.btn-secondary
    [:&:hover
     :&:focus
     :&:active:hover
     "&:not(:disabled):not(.disabled):active"
     {:background-color (theme-getx :secondary-button-hover-bgcolor :secondary-button-bgcolor :color4)
      :border-color (theme-getx :secondary-button-hover-bgcolor :secondary-button-bgcolor :color4)
      :color (theme-getx :secondary-button-hover-color :secondary-button-color :color4)
      :outline-color :transparent}]
    {:background-color (theme-getx :secondary-button-bgcolor :color4)
     :border-color (theme-getx :secondary-button-bgcolor :color4)
     :color (theme-getx :secondary-button-color)
     :outline-color :transparent}]
   [:.btn-primary.disabled :.btn-primary:disabled ; same color as bootstrap's default for .btn-secondary.disabled
    {:color "#fff"
     :background-color "#6c757d"
     :border-color "#6c757d"}]
   [:.button-min-width {:min-width (u/rem 5)}]
   [:.icon-link {:color "#6c757d" ; same color as bootstrap's default for .btn-secondary.disabled
                 :cursor "pointer"}
    [:&:hover {:color "#5a6268"}]]
   [:.icon-stack-background {:color "white"
                             :font-size "110%"}]
   [:.modal--title [:.link
                    {:border-radius "0.25em"
                     :padding "0.25em"
                     :text-align :center
                     :color "#ccc"}
                    [:&:hover {:color (theme-getx :color4)
                               :background-color "#eee"}]]]
   [:.flash-message-title {:font-weight :bold}]

   ;; TODO get rid of the text classes we don't use? At least -dark,
   ;; -white, -light and -info seem unused currently.
   [:.text-primary {:color (theme-getx :text-primary)}]
   [:.text-secondary {:color (theme-getx :text-secondary)}]
   [:.text-success {:color (theme-getx :text-success)}]
   [:.text-danger {:color (theme-getx :text-danger)}]
   [:.text-warning {:color (theme-getx :text-warning)}]
   [:.text-info {:color (theme-getx :text-info)}]
   [:.text-light {:color (theme-getx :text-light)}]
   [:.text-dark {:color (theme-getx :text-dark)}]
   [:.text-muted {:color (theme-getx :text-muted)}]
   [:.text-white {:color (theme-getx :text-white)}]

   [:.bg-primary {:background-color (theme-getx :bg-primary)}]
   [:.bg-secondary {:background-color (theme-getx :bg-secondary)}]
   [:.bg-success {:background-color (theme-getx :bg-success)}]
   [:.bg-danger {:background-color (theme-getx :bg-danger)}]
   [:.bg-warning {:background-color (theme-getx :bg-warning)}]
   [:.bg-info {:background-color (theme-getx :bg-info)}]
   [:.bg-light {:background-color (theme-getx :bg-light)}]
   [:.bg-dark {:background-color (theme-getx :bg-dark)}]
   [:.bg-white {:background-color (theme-getx :bg-white)}]

   ;; TODO get rid of alert classes we don't use
   [:.alert-primary {:color (theme-getx :alert-primary-color)
                     :background-color (theme-getx :alert-primary-bgcolor)
                     :border-color (theme-getx :alert-primary-bordercolor :alert-primary-color)}]
   [:.alert-secondary {:color (theme-getx :alert-secondary-color)
                       :background-color (theme-getx :alert-secondary-bgcolor)
                       :border-color (theme-getx :alert-secondary-bordercolor :alert-secondary-color)}]
   [:.alert-success
    (s/descendant :.state-approved.phases :.phase.completed)
    (s/descendant :.state-submitted.phases :.phase.completed)
    {:color (theme-getx :alert-success-color)
     :background-color (theme-getx :alert-success-bgcolor)
     :border-color (theme-getx :alert-success-bordercolor :alert-success-color)}]
   [:.alert-danger
    :.state-rejected
    :.state-revoked
    (s/descendant :.state-rejected.phases :.phase.completed)
    (s/descendant :.state-revoked.phases :.phase.completed)
    {:color (theme-getx :alert-danger-color)
     :background-color (theme-getx :alert-danger-bgcolor)
     :border-color (theme-getx :alert-danger-bordercolor :alert-danger-color)}]
   [:.alert-warning {:color (theme-getx :alert-warning-color)
                     :background-color (theme-getx :alert-warning-bgcolor)
                     :border-color (theme-getx :alert-warning-bordercolor :alert-warning-color)}]
   [:.alert-info
    {:color (theme-getx :alert-info-color)
     :background-color (theme-getx :alert-info-bgcolor)
     :border-color (theme-getx :alert-info-bordercolor :alert-info-color)}]
   [:.alert-light {:color (theme-getx :alert-light-color)
                   :background-color (theme-getx :alert-light-bgcolor)
                   :border-color (theme-getx :alert-light-bordercolor :alert-light-color)}]
   [:.alert-dark {:color (theme-getx :alert-dark-color)
                  :background-color (theme-getx :alert-dark-bgcolor)
                  :border-color (theme-getx :alert-dark-bordercolor :alert-dark-color)}]
   shake
   [:.flash-message.alert-danger
    {:animation [[shake "0.6s cubic-bezier(.36,.07,.19,.97) both"]]}]

   ;; animating opacity instead of box-shadow for smooth performance
   ;; https://tobiasahlin.com/blog/how-to-animate-box-shadow/
   pulse-opacity
   [".flash-message.alert-success::after"
    {:content "''"
     :position :absolute
     :border-radius ".25rem"
     :top 0
     :left 0
     :width "100%"
     :height "100%"
     :box-shadow "0 0 4px 8px rgba(60, 108, 61, 0.5)"
     :animation [[pulse-opacity "0.6s ease-out 1 both"]]}]

   ;; Navbar
   [:.navbar-wrapper
    {:max-width navbar-max-width}]
   [:.navbar
    {:font-size (u/px 19)
     :letter-spacing (u/rem 0.015)
     :padding-left 0
     :padding-right 0
     :color (theme-getx :navbar-color)
     :justify-content "space-between"}
    [:.nav-link :.btn-link
     {:background-color :inherit}]]
   [:#administration-menu
    [:.nav-link
     {:padding ".5rem 0"}]
    {:display :flex
     :flex-direction :row
     :align-items :center
     :justify-content :center
     :gap [[(u/rem 0) (u/rem 1)]]
     :flex-wrap :wrap
     :max-width navbar-max-width}]
   [:.navbar-top-bar {:width (u/percent 100)
                      :height (u/px 4)
                      :display :flex
                      :flex-direction :row}]
   [:.navbar-top-left {:flex 1
                       :background-color (theme-getx :color4)}]
   [:.navbar-top-right {:flex 1
                        :background-color (theme-getx :color2)}]
   [:.navbar-text {:font-size (u/px 19)
                   :font-weight (button-navbar-font-weight)}]
   [:.navbar-toggler {:border-color (theme-getx :color1)}]
   [:.nav-link
    :.btn-link
    {:color (theme-getx :nav-color :link-color)
     :font-weight (button-navbar-font-weight)
     :border 0} ; for button links
    [:&.active
     {:color (theme-getx :nav-active-color :color4)}]
    [:&:hover
     {:color (theme-getx :nav-hover-color :color4)}]]
   [:.navbar {:white-space "nowrap"}]
   [(s/descendant :.user-widget :.nav-link) {:display :inline-block}]
   [:.user-name {:text-transform :none}]
   [:#big-navbar {:text-transform (theme-getx :big-navbar-text-transform)}]
   [(s/descendant :.navbar-text :.language-switcher)
    {:margin-right (u/rem 1)}]
   [:.navbar-flex {:display "flex"
                   :flex-direction "row"
                   :justify-content "space-between"
                   :min-width "100%"}]

   ;; Logo, login, etc.
   (logo-styles)
   ;; Footer
   (let [footer-text-color (theme-getx :footer-color :table-heading-color)]
     [:footer {:width "100%"
               :min-height (u/px 53.6)
               :color footer-text-color
               :font-size (u/px 19) ;; same as navbar
               :padding-top "1rem"
               :padding-bottom "1rem"
               :background-color (theme-getx :footer-bgcolor :table-heading-bgcolor :color3)
               :position :relative}
      [:a :a:hover :.nav-link {:color footer-text-color
                               :font-weight (button-navbar-font-weight)}]
      [:.dev-reload-button {:position :absolute
                            :bottom (u/rem 1.5)
                            :right (u/rem 1.5)}]])

   [:.jumbotron
    {:background-color "#fff"
     :text-align "center"
     :color "#000"
     :margin-top (u/rem 2)
     :border-style "solid"
     :border-width (u/px 1)
     :box-shadow (theme-getx :collapse-shadow :table-shadow)}
    [:h1 {:margin-bottom (u/px 20)}]]
   [:.login-btn {:max-height (u/px 70)
                 :margin-bottom (u/px 20)}
    [:&:hover {:filter "brightness(80%)"}]]

   (rems-table-styles)
   [:.btn.disabled {:opacity 0.25}]
   [:.catalogue-item-link {:color "#fff"
                           :text-decoration "underline"}]
   [:.language-switcher {:padding ".5em 0"}]
   [:.example-page {:margin (u/rem 2)}]
   [(s/> :.example-page :h1) {:margin "4rem 0"}]
   [(s/> :.example-page :h2) {:margin-top (u/rem 8)
                              :margin-bottom (u/rem 2)}]
   [(s/> :.example-page :h3) {:margin-bottom (u/rem 1)}]
   [(s/descendant :.example-page :.example) {:margin-bottom (u/rem 4)}]
   [:.example-content {:border "1px dashed black"}]
   [:.example-content-end {:clear "both"}]
   [:textarea.form-control {:overflow "hidden"}
    ;; XXX: Override the browser's default validation for textarea that has
    ;;   the attribute 'required': If a required element is invalid, do not
    ;;   show the box shadow, which is used at least by Firefox to highlight
    ;;   the invalid element. If the element is also in focus, show
    ;;   Bootstrap's default box shadow instead.
    [:&:required:invalid {:-webkit-box-shadow :none}
     [:&:focus {:-webkit-box-shadow "0 0 0 .2rem rgba(0,123,255,.25)"}]]]
   [:.group {:position "relative"
             :border "1px solid #ccc"
             :border-radius (u/rem 0.4)
             :padding (u/px 10)
             :margin-top 0
             :margin-bottom (u/px 16)}]
   [:div.form-control {:height :auto
                       :white-space "pre-wrap"}
    [:&:empty {:height (u/rem 2.25)}]]
   [:.toggle-diff {:float "right"}]
   [:.diff
    [:ins {:background-color "#acf2bd"}]
    [:del {:background-color "#fdb8c0"}]]
   [:form.inline
    {:display :inline-block}
    [:.btn-link
     {:border :none
      :padding 0}]]
   [:.modal-title {:color "#292b2c"}]
   [(s/+
     (s/descendant :.language-switcher :form)
     :form)
    {:margin-left (u/rem 0.5)}]
   [:div.commands {:cursor :auto
                   :display :flex
                   :flex-direction :row
                   :flex-wrap :wrap
                   :gap (u/rem 0.5)
                   :align-items :center
                   :justify-content :flex-end}]
   [:td [:div.commands {:justify-content :flex-start}]]
   [:td.commands {:width "1rem"}] ; anything smaller than actual results
   [:th.organization {:white-space :normal
                      :min-width (u/rem 5.5)}]
   [:th.active {:white-space :normal
                :min-width (u/rem 5.5)}]
   [:td.more-info {:display :flex
                   :justify-content :flex-end}]
   [".spaced-vertically > *:not(:first-child)" {:margin-top (u/rem 0.5)}]
   [".spaced-vertically-3 > *:not(:first-child)" {:margin-top (u/rem 1.5)}]

   [".btn-opens-more::after" {:content "'...'"}]

   [:#action-commands {:display "flex"
                       :flex-flow "row wrap"
                       :margin-bottom (u/em -0.5)}
    ["> *"
     {:margin-bottom (u/em 0.5)}]
    ["> *:not(:last-child)"
     {:margin-right (u/em 0.5)}]]

   [:.event-comment {:white-space :pre-wrap}]

   ;; form inputs
   ["input[type=date].form-control" {:width (u/em 12)}]
   [:.form-group {:text-align "initial"}
    ;; make fieldset legends look the same as normal labels
    [:legend {:font-size "inherit"}]]
   [:.application-field-label {:font-weight "bold"}]
   [:.administration-field-label {:font-weight "bold"}]
   [:div.info-collapse {:font-weight "400"
                        :white-space :pre-wrap}]
   ;; Bootstrap's has "display: none" on .invalid-feedback by default
   ;; and overrides that for example when there is a sibling .form-control.is-invalid,
   ;; but that doesn't work with checkbox groups, dropdowns, etc., and we anyways
   ;; don't need the feature of hiding this div with CSS when it has no content.
   [:div.invalid-feedback {:display :block
                           :font-size :inherit}]

   ;; custom checkbox
   [:.readonly-checkbox {:background-color "#ccc"}]

   [:.dashed-group (dashed-form-group)]
   [:.solid-group (solid-form-group)]

   ;; form editor
   [:#main-content.page-create-form {:max-width :unset}]
   [:.form-field (solid-form-group)]
   [:.form-field-header {:margin-bottom (u/rem 0.5)}
    [:h4 {:display "inline"
          :font-weight "bold"
          :font-size (u/rem 1.1)}]]
   [:.form-field-controls
    {:float "right"
     :font-size (u/rem 1.2)}
    [:* {:margin-left (u/em 0.25)}]]
   [:.new-form-field (assoc (dashed-form-group)
                            :text-align "center")]

   [:.form-field-visibility (assoc (solid-form-group)
                                   :margin-left 0
                                   :margin-right 0)]
   [:.form-field-option (assoc (solid-form-group)
                               :margin-left 0
                               :margin-right 0)]
   [:.new-form-field-option (assoc (dashed-form-group)
                                   :text-align "center")]

   [:#preview-form {:position :sticky ;; TODO seems to work on Chrome and Firefox. check Edge?
                    :top "100px"}
    [:.collapse-content {:margin-left 0}]
    [:#preview-form-contents {:overflow-y :scroll
                              :overflow-x :hidden
                              ;; subtract #preview-form top value plus a margin here to stay inside the viewbox
                              :max-height "calc(100vh - 220px)"}]]

   [:.field-preview {:position :relative
                     :margin-left (u/rem 1)}]
   [:.full {:width "100%"}]
   [:.intro {:margin-bottom (u/rem 2)}]
   [:.rectangle {:width (u/px 50)
                 :height (u/px 50)}]
   [:.color-1 {:background-color (theme-getx :color1)}]
   [:.color-2 {:background-color (theme-getx :color2)}]
   [:.color-3 {:background-color (theme-getx :color3)}]
   [:.color-4 {:background-color (theme-getx :color4)}]
   [:.color-title {:padding-top (u/rem 0.8)}]
   [(s/descendant :.alert :ul) {:margin-bottom 0}]
   [:ul.comments {:list-style-type :none}]
   [:.inline-comment {:font-size (u/rem 1)}]
   [(s/& :p.inline-comment ":last-child") {:margin-bottom 0}]
   [:.inline-comment-content {:display :inline-block}]
   [:.license-panel {:display :inline-block
                     :width "inherit"}]
   [:.clickable {:cursor :pointer}]
   [:.rems-card-margin-fix {:margin (u/px -1)}] ; make sure header overlaps container border
   [:.rems-card-header {:color (theme-getx :table-heading-color)
                        :background-color (theme-getx :table-heading-bgcolor :color3)}]
   [(s/descendant :.card-header :a) {:color :inherit}]
   [:.application-resources
    [:.application-resource {:margin-bottom (u/rem 1)
                             :line-height (u/rem 1)
                             :font-size (u/rem 1)}]]
   [:.license {:margin-bottom (u/rem 1)}
    [:.license-block {:color "#000"
                      :white-space "pre-wrap"}]
    [:.license-title {;; hax for opening misalignment
                      :margin-top (u/px 3)
                      :line-height (u/rem 1)
                      :font-size (u/rem 1)}]]
   [:.collapsing {:-webkit-transition "height 0.1s linear"
                  :-o-transition "height 0.1s linear"
                  :transition "height 0.1s linear"}]
   [:.collapse-toggle {:text-align :center}]
   [:.collapse-wrapper {:border-radius (u/rem 0.4)
                        :border "1px solid #ccc"
                        :background-color (theme-getx :collapse-bgcolor)
                        :box-shadow (theme-getx :collapse-shadow :table-shadow)}
    [:.card-header {:border-bottom "none"
                    :border-radius (u/rem 0.4)
                    :font-weight 400
                    :font-size (u/rem 1.5)
                    :line-height 1.1
                    :font-family (theme-getx :font-family)
                    :color (theme-getx :collapse-color)}]]
   [:.collapse-content {:margin (u/rem 1.25)}]
   [:.collapse-wrapper.slow
    [:.collapsing {:-webkit-transition "height 0.25s linear"
                   :-o-transition "height 0.25s linear"
                   :transition "height 0.25s linear"}]]

   [:.color1 {:color (theme-getx :color1)}]
   [:.color1-faint {:color (when (theme-getx :color1)
                             (-> (theme-getx :color1)
                                 (c/saturate -50)
                                 (c/lighten 33)))}]
   [:h2 {:margin [[(u/rem 3) 0 (u/rem 2) 0]]}]

   ;; application page

   [:.page-application {:max-width (u/px application-page-max-width)}]

   [:#actions {:position :sticky
               :top "85px"}]
   [:.reload-indicator {:position :fixed
                        :bottom "15px"
                        :right "15px"
                        :z-index 1000}] ; over re-frisk devtool

   ;; application list
   [:.rems-table
    [:.resource {:max-width (u/rem 30)}]
    [:.description {:min-width (u/rem 6)
                    :max-width (u/rem 30)}]
    [:.applicant {:max-width (u/rem 10)}]]
   [:.search-field {:display :flex
                    :flex-wrap :nowrap
                    :align-items :center}
    [:label {:margin-bottom 0}] ; override the default from Bootstrap
    [:div.input-group {:width "17em"}]]
   [:.search-tips {:font-size "0.9rem"
                   :margin "0.4rem 0"}
    [:.example-search {:background-color "#eef"
                       :padding "0.2rem"
                       :border-radius "0.25rem"}]]

   ;; special case for previous applications
   [:#previous-applications-except-current [:.resource {:overflow :hidden
                                                        :text-overflow :ellipsis
                                                        :white-space :nowrap
                                                        :max-width "10em"}]]

   ;; !important is needed here, otherwise these attributes are overridden
   ;; by more specific styles by react-select.
   [:.dropdown-select__option--is-focused
    (make-important
     {:color (theme-getx :table-heading-color)
      :background-color (theme-getx :table-heading-bgcolor :color3)})]
   [:.dropdown-select__control--is-focused
    (make-important
     {:color "#495057"
      :background-color "#fff"
      :border-color "#80bdff"
      :outline "0"
      :outline-offset "-2px"
      :box-shadow "0 0 0 0.2rem rgba(0,123,255,.25)"})]
   [:.dropdown-select__placeholder
    (make-important
     {:color "#555"})]

   (phase-styles)
   (actions-float-menu)
   [(s/descendant :.document :h3) {:margin-top (u/rem 4)}]

   [:.attachment-row {:display :flex
                      :flex-direction :row
                      :gap (u/rem 0.5)}]

   ;; print styling
   (stylesheet/at-media
    {:print true}
    ;; workaround for firefox only printing one page of flex elements
    ;; https://github.com/twbs/bootstrap/issues/23489
    ;; https://bugzilla.mozilla.org/show_bug.cgi?id=939897
    [:.row {:display :block}]
    [:#app {:display :block}]
    [(s/> :#app :div) {:display :block}]
    [:#main-content {:display :block}]
    [:body {:display :block}]

    ;; hide some unnecessary elements
    ;; TODO: consider a hide-print class?
    [:.fixed-top {:display :none}]
    [:#actions {:display :none}]
    [:.commands {:display :none}]
    [:#member-action-forms {:display :none}]
    [:#resource-action-forms {:display :none}]
    [:.flash-message {:display :none}]

    ;; open "show more" drawers
    [".collapse:not(.show)" {:display :block}]
    [:.collapse-toggle.collapse {:display :none}])

   ;; animation utilities
   [:.animate-transform {:-webkit-transition "transform 0.2s ease-in-out"
                         :-o-transition "transform 0.2s ease-in-out"
                         :transition "transform 0.2s ease-in-out"}]
   [:.rotate-180 {:transform "rotate(180deg)"}]

   [:.mt-2rem {:margin-top (u/rem 2)}]
   [:.gap-1 {:gap (u/rem 0.5)}]
   [:.gap-2 {:gap (u/rem 1)}]
   [:.gap-3 {:gap (u/rem 1.5)}]

   ;; Media queries must be almost last so they override
   (media-queries)

   ;; These must be last as the parsing fails when the first non-standard element is met
   (form-placeholder-styles)))

(defn- render-css-file [language content]
  (let [dir-name (str "target/resources/public/css/" (name language))
        file-name (str dir-name "/screen.css")
        dir (java.io.File. dir-name)]
    (log/info "Rendering CSS to file" (str (System/getProperty "user.dir") "/" file-name))
    (when-not (.exists dir)
      (.mkdirs dir))
    (spit file-name content)))

(defn screen-css []
  (g/css {:pretty-print? false} (remove-nil-vals (build-screen))))

;; For development use and live reload, render all configured CSS
;; files so that the devtools will notice this change and force our app
;; to reload CSS files from the usual route.
;; The files are not used for anything besides this signaling.
(mount/defstate
  rendered-css-files
  :start
  (when (env :dev)
    (doseq [language (env :languages)]
      (binding [context/*lang* language]
        (render-css-file language
                         (screen-css))))))

(defn render-css
  "Helper function for rendering styles that has parameters for
  easy memoization purposes."
  [language]
  (log/info (str "Rendering stylesheet for language " language))
  (-> (screen-css)
      (response/response)
      (response/content-type "text/css")))

(mount/defstate memoized-render-css
  :start (memoize render-css))

(defroutes css-routes
  (GET "/css/:language/screen.css" [language]
    (binding [context/*lang* (keyword language)]
      (memoized-render-css context/*lang*))))
