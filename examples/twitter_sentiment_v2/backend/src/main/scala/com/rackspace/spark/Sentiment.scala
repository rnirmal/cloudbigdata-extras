package com.rackspace.spark

import java.io.{File, FilenameFilter}
import java.util.HashMap

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.feature.HashingTF
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.classification.{NaiveBayes, NaiveBayesModel}


import java.io.StringReader
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.util.Version
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import scala.collection.mutable

import kafka.serializer.StringDecoder
import org.apache.kafka.clients.producer.{ProducerConfig, KafkaProducer, ProducerRecord}
import scala.util.parsing.json.{JSONObject,JSONArray}
import scala.collection.JavaConversions._

import org.apache.spark.streaming._
import org.apache.spark.streaming.twitter._
import org.apache.spark.streaming.StreamingContext._

object Sentiment {

    val apiKey = ""
    val apiSecret = ""
    val accessToken = ""
    val accessTokenSecret = ""

    val twitterRefresh = 10
    val kafkaBrokers = "slave-1.local:6667,slave-2.local:6667,slave-3.local:6667"
    val kakfaQueue = "scored-tweets"

    def configureTwitterCredentials(apiKey: String, apiSecret: String, accessToken: String, accessTokenSecret: String) {
        val configs = new HashMap[String, String] ++= Seq(
            "apiKey" -> apiKey,
            "apiSecret" -> apiSecret,
            "accessToken" -> accessToken,
            "accessTokenSecret" -> accessTokenSecret
        )
        configs.foreach{ case(key, value) =>
            if (value.trim.isEmpty) {
                throw new Exception("Error setting authentication - value for " + key + " not set")
            }
            val fullKey = "twitter4j.oauth." + key.replace("api", "consumer")
            System.setProperty(fullKey, value.trim)
        }
    }

    def prepareNBModel(sqlContext: SQLContext): NaiveBayesModel = {
        val set1 = sqlContext
            .read.format("com.databricks.spark.csv")
            .option("header", "true")
            .load("hdfs://master-1.local:8020/apps/sentiment/full-corpus-1.csv")
        val set2 = sqlContext
            .read.format("com.databricks.spark.csv")
            .option("header", "true")
            .load("hdfs://master-1.local:8020/apps/sentiment/dataset.csv")

        val kaggle = set2.registerTempTable("kaggle_data")
        val df1 = sqlContext.sql("select * from kaggle_data where SentimentSource = 'Kaggle'")
        val df2 = set1.drop("TweetDate")
        val combined_df = df2.unionAll(df1)

        val hashingTF = new HashingTF()

        val labelAndTweet = combined_df.map(t => (t.getString(1), t.getString(3)))
        val documents = labelAndTweet.map{case(sentiment, tweet) => (sentiment.toInt, tokenize(tweet))}
        val featurized = documents.map{case(label, words) => LabeledPoint(label, hashingTF.transform(words))}
        val Array(train, test) = featurized.randomSplit(Array(0.7, 0.3))
        val model = NaiveBayes.train(featurized)

        val predictionAndLabels = test.map(point => (model.predict(point.features), point.label))
        val correct = predictionAndLabels.filter{case(predicted, actual) =>  predicted == actual}

        model
    }


    def predictSentiment(model: NaiveBayesModel, tokens: Seq[String], hashingTF: HashingTF) : Int = {
        val score = model.predict(hashingTF.transform(tokens))
        return score.toInt
    }


    def tokenize(content: String): Seq[String] = {
        val tReader = new StringReader(content)
        val analyzer = new EnglishAnalyzer()
        val tStream = analyzer.tokenStream("contents", tReader)
        val term = tStream.addAttribute(classOf[CharTermAttribute])
        tStream.reset()

        val result = mutable.ArrayBuffer.empty[String]
        while(tStream.incrementToken()) {
            result += term.toString
        }
        result
    }

    def getKafkaProducer(): KafkaProducer[String, Object] = {
        val props = new HashMap[String, Object]()
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers)
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringSerializer")
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringSerializer")
        new KafkaProducer[String, Object](props)
    }

    def main(args: Array[String]) {
        val conf = new SparkConf().setAppName("SentimentApp")
        val sc = new SparkContext(conf)
        val sqlContext = new SQLContext(sc)

        val model = prepareNBModel(sqlContext)
        configureTwitterCredentials(apiKey, apiSecret, accessToken, accessTokenSecret)
        val kafkaProducer = getKafkaProducer()
        val ssc = new StreamingContext(sc, Seconds(twitterRefresh))
        var tokenKeywordMap = new HashMap[String, String];
        args.foreach { keyword =>
            tokenize(keyword).foreach {
                stem => {
                    tokenKeywordMap.put(stem, keyword)
                }
            }
        }
        val keywordTokens = tokenKeywordMap.keySet()
        val keywords = args.mkString(",")
        val filters = Array("track=".concat(keywords))
        val tweets = TwitterUtils.createStream(ssc, None, filters)
        val hashingTF = new HashingTF()
        tweets.foreachRDD(tweet_rdd => {
            tweet_rdd.collect().foreach {
                tweet => {
                    val user = tweet.getUser()
                    val locationMap = Option(tweet.getGeoLocation()) match {
                        case Some(location) => {
                            Map[String, Object](
                                "lat" -> location.getLatitude().toString(),
                                "lon" -> location.getLongitude().toString()
                            )
                        }
                        case None           => {
                            Map[String, Object](
                                "lat" -> "0",
                                "lon" -> "0"
                            )
                        }
                    }
                    val userMap = Map[String, Object](
                        "id" -> user.getId().toString(),
                        "name" -> user.getName(),
                        "screen_name" -> user.getScreenName(),
                        "profile_image_url" -> user.getProfileImageURL()
                    )
                    val tweetTokens = tokenize(tweet.getText())
                    val commonTokens = tweetTokens.toSet intersect keywordTokens
                    val keywords = commonTokens map { tokenKeywordMap.get(_) }
                    val tweetMap = Map[String, Object](
                        "user" -> new JSONObject(userMap),
                        "location" -> new JSONObject(locationMap),
                        "id" -> tweet.getId().toString(),
                        "created_at" -> tweet.getCreatedAt().toString(),
                        "text" -> tweet.getText(),
                        "score" -> predictSentiment(model, tweetTokens, hashingTF).toString(),
                        "keywords" -> new JSONArray(keywords.toList)
                    )
                    val tweetJson = new JSONObject(tweetMap)
                    val kafkaRecord = new ProducerRecord[String, Object](kakfaQueue, null, tweetJson.toString())
                    kafkaProducer.send(kafkaRecord)
                }
            }
        })
        ssc.start()
        ssc.awaitTermination()
    }
}
