在我们在生产环境中，提交spark作业时，用的spark-submit shell脚本，里面调整对应的参数  
/usr/local/spark/bin/spark-submit \   
–class cn.spark.sparktest.core.WordCountCluster \   
–num-executors 3 \ 配置executor的数量   
–driver-memory 100m \ 配置driver的内存（影响不大）   
–executor-memory 100m \ 配置每个executor的内存大小   
–executor-cores 3 \ 配置每个executor的cpu core数量   
/usr/local/SparkTest-0.0.1-SNAPSHOT-jar-with-dependencies.jar \  