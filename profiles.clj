;; Local profile overrides

{:profiles/dev  {:env {:db {:subname "//localhost:5432/natsumedev"
                            :user "natsumedev"
                            :password "riDJMq98LpyWgB7F"}
                       :http {:port 3000
                              :run false}
                       :log {:directory "./log"}
                       :dirs ["/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT"]
                       :verbose false
                       :clean false
                       :process false
                       :search true
                       :sampling {:ratio 0.0
                                  :seed 2
                                  :replace false
                                  :hold-out false}}}
 :profiles/test {}}
