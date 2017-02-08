package org.apache.spark.examples

import scala.reflect.runtime.universe

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.ml.feature.HashingTF
import org.apache.spark.ml.feature.IDF
import org.apache.spark.ml.feature.Tokenizer
import org.apache.spark.mllib.classification.NaiveBayes
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.sql.Row

/**
 *ʹ��Spark MLlib�ṩ�����ر�Ҷ˹(Native Bayes)�㷨,��ɶ������ı��ķ�����̡�
 * ��Ҫ�������ķִʡ��ı���ʾ��TF-IDF����ģ��ѵ��������Ԥ���
 * http://lxw1234.com/archives/2016/01/605.htm
 */
case class RawDataRecord(category: String, text: String)
object NativeBayesHashingTF {
  def main(args: Array[String]) {

    val conf = new SparkConf().setMaster("local[2]").setAppName("NativeBayes")
    val sc = new SparkContext(conf)

    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    //��ʽ����,�Զ�ת��toDF
    import sqlContext.implicits._
    var srcRDD = sc.textFile("../data/mllib/sougou/C000007/").filter(!_.isEmpty).map {
      x =>
        // println("==="+x+"===========")
        var data = x.split(",")
        println("==="+data(0)+"\t======"+data(1))
        RawDataRecord(data(0), data(1))
    }
    //70%��Ϊѵ������,30%��Ϊ��������
    val splits = srcRDD.randomSplit(Array(0.7, 0.3))
    var trainingDF = splits(0).toDF()
    var testDF = splits(1).toDF()
    //������ת��������
    var tokenizer = new Tokenizer().setInputCol("text").setOutputCol("words")
    //transform()������DataFrameת��Ϊ����һ��DataFrame���㷨
    var wordsData = tokenizer.transform(trainingDF)
    //output1����������ת�������飩
    wordsData.show()
    println("output1��")
    /**
     *+--------------------+--------------------+--------------------+
      |            category|                text|               words|
      +--------------------+--------------------+--------------------+
      |������������ȷ��ʾ����Ͷ�ʹ���...|��ǰ�·ɼ���ȴ�������ϣ�ר��������...|[��ǰ�·ɼ���ȴ�������ϣ�ר������...|
      +--------------------+--------------------+--------------------+
     */
    wordsData.select($"category", $"text", $"words").show(1)
    //wordsData.select($"category", $"text", $"words").take(1)
   //��ÿ����ת����Int��,�����������ĵ��еĴ�Ƶ(TF)
    var hashingTF = new HashingTF().setNumFeatures(500000).setInputCol("words").setOutputCol("rawFeatures")
    //transform()������DataFrameת��Ϊ����һ��DataFrame���㷨
    var featurizedData = hashingTF.transform(wordsData)
    //output2��������ÿ�������ĵ��еĴ�Ƶ��
    println("output2��")
    /**
    +--------------------+--------------------+--------------------+
    |            category|               words|         rawFeatures|
    +--------------------+--------------------+--------------------+
    |������������ȷ��ʾ����Ͷ�ʹ���...|[��ǰ�·ɼ���ȴ�������ϣ�ר������...|(500000,[372412],...|
    +--------------------+--------------------+--------------------+
     */
    featurizedData.select($"category", $"words", $"rawFeatures").show()
    //println(">>>>>>>>>>>>>>>."+featurizedData.toString())
    //���ĵ�Ƶ��(IDF),��������һ�������ض��ĵ�����ض�,����TF-IDFֵ
    var idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    //fit()������DataFrameת��Ϊһ��Transformer���㷨
    var idfModel = idf.fit(featurizedData)
    //transform()������DataFrameת��Ϊ����һ��DataFrame���㷨
    var rescaledData = idfModel.transform(featurizedData)
    //output3��������ÿ���ʵ�TF-IDF��
    println("output3��")
    /**
    +--------------------+--------------------+
    |            category|            features|
    +--------------------+--------------------+
          ������������ȷ��ʾ����Ͷ�ʹ���...|(500000,[372412],...|
    +--------------------+--------------------+
     */
    rescaledData.select($"category", $"features").show()
    /**
     * �����������ת����Bayes�㷨��Ҫ�ĸ�ʽ:
     		0,1 0 0
        0,2 0 0
        1,0 1 0
        1,0 2 0
        2,0 0 1
        2,0 0 2
     */
    var trainDataRdd = rescaledData.select($"category", $"features").map {
      case Row(label: String, features: Vector) =>
      //LabeledPoint��ǵ��Ǿֲ�����,�����������ܼ��ͻ���ϡ����,ÿ�������������һ����ǩ(label)
        LabeledPoint(label.toDouble, Vectors.dense(features.toArray))
    }
    //output4:(Bayes�㷨���������ݸ�ʽ)
    println("output4��")
    trainDataRdd.take(1)

    
    var srcDF = sc.textFile("../data/mllib/1.txt").map { 
      x => 
        var data = x.split(",")
        RawDataRecord(data(0),data(1))
    }.toDF()

    //ѵ��ģ��,modelTypeģ������(���ִ�Сд)multinomial�����
    val model = NaiveBayes.train(trainDataRdd, lambda = 1.0, modelType = "multinomial")
    //�������ݼ�,��ͬ����������ʾ����ʽת��
    var testwordsData = tokenizer.transform(testDF)
    //transform()������DataFrameת��Ϊ����һ��DataFrame���㷨
    var testfeaturizedData = hashingTF.transform(testwordsData)
    //transform()������DataFrameת��Ϊ����һ��DataFrame���㷨
    var testrescaledData = idfModel.transform(testfeaturizedData)
    /**
     * �����������ת����Bayes�㷨��Ҫ�ĸ�ʽ:
     		0,1 0 0
        0,2 0 0
        1,0 1 0
        1,0 2 0
        2,0 0 1
        2,0 0 2
     */
    var testDataRdd = testrescaledData.select($"category", $"features").map {
      case Row(label: String, features: Vector) =>
      //LabeledPoint��ǵ��Ǿֲ�����,�����������ܼ��ͻ���ϡ����,ÿ�������������һ����ǩ(label)
        LabeledPoint(label.toDouble, Vectors.dense(features.toArray))
    }

    //�Բ������ݼ�ʹ��ѵ��ģ�ͽ��з���Ԥ��
    val testpredictionAndLabel = testDataRdd.map(p => (model.predict(p.features), p.label))

    //ͳ�Ʒ���׼ȷ��
    var testaccuracy = 1.0 * testpredictionAndLabel.filter(x => x._1 == x._2).count() / testDataRdd.count()
    //output5�����������ݼ�����׼ȷ�ʣ�
    println("output5��")
   //׼ȷ��90%,�����ԡ���������Ҫ�ռ������ϸ,ʱ����µ�������ѵ���Ͳ����ˡ� 
    println(testaccuracy)

  }
}