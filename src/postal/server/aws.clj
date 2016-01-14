(ns postal.server.aws)

(defn arn-for
  [service name & {:keys [region id] :or {region "us-east-1"
                                          id     "467434337885"}}]
  (str "arn:aws:" service ":" region ":" id ":" name))

(defn sns-arn
  [name & opts]
  (apply arn-for "sns" name opts))

(defn sqs-arn
  [name & opts]
  (apply arn-for "sqs" name opts))

(defn allow-sns-push-to-sqs-policy
  [sqs-arn sns-arn]
  (generate-string
    {:Version   "2012-10-17"
     :Statement [{:Effect    "Allow"
                  :Principal "*"
                  :Action    "SQS:SendMessage"
                  :Resource  sqs-arn
                  :Condition {:ArnEquals {"aws:SourceArn" sns-arn}}}]}))