###### 1 Example

    package mllib
    
    import org.apache.spark.{SparkConf, SparkContext}
    // $example on$
    import org.apache.spark.mllib.tree.RandomForest
    import org.apache.spark.mllib.tree.model.RandomForestModel
    import org.apache.spark.mllib.util.MLUtils
    // $example off$
    
    object RandomForestClassificationExample {
      def main(args: Array[String]): Unit = {
        val conf = new SparkConf().setAppName("RandomForestClassificationExample")
        val sc = new SparkContext(conf)
        // $example on$
        // Load and parse the data file.
        val data = MLUtils.loadLibSVMFile(sc, "data/mllib/sample_libsvm_data.txt")
        // Split the data into training and test sets (30% held out for testing)
        val splits = data.randomSplit(Array(0.7, 0.3))
        val (trainingData, testData) = (splits(0), splits(1))

        // Train a RandomForest model.
        // Empty categoricalFeaturesInfo indicates all features are continuous.
        val numClasses = 2
        val categoricalFeaturesInfo = Map[Int, Int]()
        val numTrees = 3 // Use more in practice.
        val featureSubsetStrategy = "auto" // Let the algorithm choose.
        val impurity = "gini"
        val maxDepth = 4
        val maxBins = 32

        val model = RandomForest.trainClassifier(trainingData, numClasses, categoricalFeaturesInfo,
          numTrees, featureSubsetStrategy, impurity, maxDepth, maxBins)
    
        // Evaluate model on test instances and compute test error
        val labelAndPreds = testData.map { point =>
          val prediction = model.predict(point.features)
          (point.label, prediction)
        }
        val testErr = labelAndPreds.filter(r => r._1 != r._2).count.toDouble / testData.count()
        println("Test Error = " + testErr)
        println("Learned classification forest model:\n" + model.toDebugString)
    
        // Save and load model
        model.save(sc, "target/tmp/myRandomForestClassificationModel")
        val sameModel = RandomForestModel.load(sc, "target/tmp/myRandomForestClassificationModel")
    // $example off$
      }
    }
    // scalastyle:on println

###### 2 trainRegressor

    //org.apache.spark.mllib.tree.RandomForest
    def trainRegressor(
        input: RDD[LabeledPoint],
        strategy: Strategy,
        numTrees: Int,
        featureSubsetStrategy: String,
        seed: Int): RandomForestModel = {
            require(strategy.algo == Regression,s"RandomForest.trainRegressor given Strategy with invalid algo: ${strategy.algo}")
        val rf = new RandomForest(strategy, numTrees, featureSubsetStrategy, seed)
        rf.run(input)
        }

###### 3 run
        
    //org.apache.spark.ml.tree.impl.RandomForest
    /**
     * 算法将数据集在行与行之间分区
     * 一次迭代划分一些结点。为了找到最佳划分，对于一个给定的结点，从各个分布式的数据上收集信息统计起来。
     * 对每个结点，统计信息收集到某个worker上，然后这个worker找到最佳划分。
     * 需要连续特征离散化。分箱在初始化时，在所有连续特征用最大分箱可能值离散化后的 findSplits()方法中完成。
     * 主循环对结点队列进行操作，这些结点位于正在训练的树的周边。如果一次有多个树正在训练，那么队列中包含所有的这些树周边的结点。
     * 一次迭代的过程：
     * 在Master上：
     *  - 根据内存将队列中的一些结点pull下来
     *  - 随机森林中，如果featureSubsetStrategy=all，那么每个结点有相同的候选特征，selectNodesToSplit()
     * 在Worker上，findBestSplits():
     *  - worker遍历一遍它的样本子集
     *  - 对每个(tree, node, feature, split)元组，worker选择划分的统计。注意(tree, node)对对于从队列中选择的结点在这次迭代中是有限的。考虑的特征集根据featureSubsetStrategy也是有限的。
     *  - 每个结点的统计根据reduceByKey()聚合到一个特定的worker上，指定的worker选择最佳 (feature, split)对，或者如果符合停止的标准时，选择停止划分。
     * 在Master上：
     *  - master收集所有的划分结点，更新模型。
     *  - 更新的模型在下一次迭代的时候传给worker。
     * 直到结点队列空.
     * 这里的大多数方法支持统计聚合，这是计算最繁重的部分。同时考虑worker上的统计计算代价和统计通信代价。
     */
     
     /**
      * 训练一个随机森林
      */
     def run(
          input: RDD[LabeledPoint],
          strategy: OldStrategy,
          numTrees: Int,
          featureSubsetStrategy: String,
          seed: Long,
          instr: Option[Instrumentation[_]],
          parentUID: Option[String] = None): Array[DecisionTreeModel] = {
    
        val timer = new TimeTracker()
    
        timer.start("total")
    
        timer.start("init")
    
        val retaggedInput = input.retag(classOf[LabeledPoint])
        
        // 建立决策树的元数据信息（分裂点位置、箱子数及各箱子包含特征属性的值等等）
        val metadata =
          DecisionTreeMetadata.buildMetadata(retaggedInput, strategy, numTrees, featureSubsetStrategy)
        instr match {
          case Some(instrumentation) =>
            instrumentation.logNumFeatures(metadata.numFeatures)
            instrumentation.logNumClasses(metadata.numClasses)
          case None =>
            logInfo("numFeatures: " + metadata.numFeatures)
            logInfo("numClasses: " + metadata.numClasses)
        }
    
        // Find the splits and the corresponding bins (interval between the splits) using a sample
        // of the input data.
        timer.start("findSplits")
        // 对于连续型特征，利用切分点抽样统计简化计算
        //对于离散型特征，如果是无序的，则最多有个 splits=2^(numBins-1)-1 划分
        //如果是有序的，则最多有 splits=numBins-1 个划分
        val splits = findSplits(retaggedInput, metadata, seed)
        timer.stop("findSplits")
        logDebug("numBins: feature: number of bins")
        logDebug(Range(0, metadata.numFeatures).map { featureIndex =>
          s"\t$featureIndex\t${metadata.numBins(featureIndex)}"
        }.mkString("\n"))
    
        // Bin feature values (TreePoint representation).
        // Cache input RDD for speedup during multiple passes.
        // 转换成树形的 RDD类型，转换后，所有样本点已经按分裂点条件分到了各自的箱子中
        val treeInput = TreePoint.convertToTreeRDD(retaggedInput, splits, metadata)
    
        val withReplacement = numTrees > 1
        // convertToBaggedRDD 方法使得每棵树就是样本的一个子集
        val baggedInput = BaggedPoint
          .convertToBaggedRDD(treeInput, strategy.subsamplingRate, numTrees, withReplacement, seed)
          .persist(StorageLevel.MEMORY_AND_DISK)
    
        // depth of the decision tree
        val maxDepth = strategy.maxDepth
        require(maxDepth <= 30,
          s"DecisionTree currently only supports maxDepth <= 30, but was given maxDepth = $maxDepth.")
    
        // Max memory usage for aggregates
        // TODO: Calculate memory usage more precisely.
        val maxMemoryUsage: Long = strategy.maxMemoryInMB * 1024L * 1024L
        logDebug("max memory usage for aggregates = " + maxMemoryUsage + " bytes.")
    
        /*
         * The main idea here is to perform group-wise training of the decision tree nodes thus
         * reducing the passes over the data from (# nodes) to (# nodes / maxNumberOfNodesPerGroup).
         * Each data sample is handled by a particular node (or it reaches a leaf and is not used
         * in lower levels).
         */


        /***********************************************************************/  
        // Create an RDD of node Id cache.
        // At first, all the rows belong to the root nodes (node Id == 1).
        // 节点是否使用缓存，节点 ID 从 1 开始，1 即为这颗树的根节点，左节点为 2，右节点为 3，依次递增下去
        val nodeIdCache = if (strategy.useNodeIdCache) {
          Some(NodeIdCache.init(
            data = baggedInput,
            numTrees = numTrees,
            checkpointInterval = strategy.checkpointInterval,
            initVal = 1))
        } else {
          None
        }
    
        // FIFO queue of nodes to train: (treeIndex, node)
        val nodeQueue = new mutable.Queue[(Int, LearningNode)]()
    
        val rng = new Random()
        rng.setSeed(seed)
    
        // Allocate and queue root nodes.
        // 创建树的根节点
        val topNodes = Array.fill[LearningNode](numTrees)(LearningNode.emptyNode(nodeIndex = 1))
        // 将（树的索引，树的根节点）入队，树索引从 0 开始，根节点从 1 开始
        Range(0, numTrees).foreach(treeIndex => nodeQueue.enqueue((treeIndex, topNodes(treeIndex))))
    
        timer.stop("init")
    
        while (nodeQueue.nonEmpty) {
          // Collect some nodes to split, and choose features for each node (if subsampling).
          // Each group of nodes may come from one or multiple trees, and at multiple levels.
          // 取得每个树所有需要切分的节点,nodesForGroup表示需要切分的节点
          val (nodesForGroup, treeToNodeToIndexInfo) =
            RandomForest.selectNodesToSplit(nodeQueue, maxMemoryUsage, metadata, rng)
          // Sanity check (should never occur):
          assert(nodesForGroup.nonEmpty,
            s"RandomForest selected empty nodesForGroup.  Error for unknown reason.")
    
          // Choose node splits, and enqueue new nodes as needed.
          timer.start("findBestSplits")
          // 找出最优切点
          RandomForest.findBestSplits(baggedInput, metadata, topNodes, nodesForGroup,
            treeToNodeToIndexInfo, splits, nodeQueue, timer, nodeIdCache)
          timer.stop("findBestSplits")
        }
    
        baggedInput.unpersist()
    
        timer.stop("total")
    
        logInfo("Internal timing for DecisionTree:")
        logInfo(s"$timer")
    
        // Delete any remaining checkpoints used for node Id cache.
        if (nodeIdCache.nonEmpty) {
          try {
            nodeIdCache.get.deleteAllCheckpoints()
          } catch {
            case e: IOException =>
              logWarning(s"delete all checkpoints failed. Error reason: ${e.getMessage}")
          }
        }
    
        val numFeatures = metadata.numFeatures
    
        parentUID match {
          case Some(uid) =>
            if (strategy.algo == OldAlgo.Classification) {
              topNodes.map { rootNode =>
                new DecisionTreeClassificationModel(uid, rootNode.toNode, numFeatures,
                  strategy.getNumClasses)
              }
            } else {
              topNodes.map { rootNode =>
                new DecisionTreeRegressionModel(uid, rootNode.toNode, numFeatures)
              }
            }
          case None =>
            if (strategy.algo == OldAlgo.Classification) {
              topNodes.map { rootNode =>
                new DecisionTreeClassificationModel(rootNode.toNode, numFeatures,
                  strategy.getNumClasses)
              }
            } else {
              topNodes.map(rootNode => new DecisionTreeRegressionModel(rootNode.toNode, numFeatures))
            }
        }
    }
     
###### 4 初始化：决策树元数据信息的构建
    //org.apache.spark.ml.tree.impl.DecisionTreeMetadata 
      /**
       * Construct a [[DecisionTreeMetadata]] instance for this dataset and parameters.
       * 决定哪些类别特征有序，哪些无序，每个特征的划分点和分箱数。
       */
      def buildMetadata(
          input: RDD[LabeledPoint],
          strategy: Strategy,
          numTrees: Int,
          featureSubsetStrategy: String): DecisionTreeMetadata = {
    
        // 特征数
        val numFeatures = input.map(_.features.size).take(1).headOption.getOrElse {
          throw new IllegalArgumentException(s"DecisionTree requires size of input RDD > 0, " +
            s"but was given by empty one.")
        }
        
        // 样本数，分类/回归
        val numExamples = input.count()
        val numClasses = strategy.algo match {
          case Classification => strategy.numClasses
          case Regression => 0
        }
    
        // 最大可能分箱数
        val maxPossibleBins = math.min(strategy.maxBins, numExamples).toInt
        if (maxPossibleBins < strategy.maxBins) {
          logWarning(s"DecisionTree reducing maxBins from ${strategy.maxBins} to $maxPossibleBins" +
            s" (= number of training instances)")
        }
    
        // We check the number of bins here against maxPossibleBins.
        // This needs to be checked here instead of in Strategy since maxPossibleBins can be modified
        // based on the number of training examples.
        // 最大分类数要小于最大可能分箱数。
        // 这里categoricalFeaturesInfo是传入的信息，这个map保存特征的类别信息。
        // 例如，(n->k)表示特征k包含的类别有（0,1,...,k-1）
        if (strategy.categoricalFeaturesInfo.nonEmpty) {
          val maxCategoriesPerFeature = strategy.categoricalFeaturesInfo.values.max
          val maxCategory =
            strategy.categoricalFeaturesInfo.find(_._2 == maxCategoriesPerFeature).get._1
          require(maxCategoriesPerFeature <= maxPossibleBins,
            s"DecisionTree requires maxBins (= $maxPossibleBins) to be at least as large as the " +
            s"number of values in each categorical feature, but categorical feature $maxCategory " +
            s"has $maxCategoriesPerFeature values. Considering remove this and other categorical " +
            "features with a large number of values, or add more training examples.")
        }
    
        val unorderedFeatures = new mutable.HashSet[Int]()
        val numBins = Array.fill[Int](numFeatures)(maxPossibleBins)
        if (numClasses > 2) {
          // Multiclass classification
          // 最大无序特征的分类数，根据最大可能分箱数计算
          // 根据maxPossibleBins,利用以下的公式(该公式是无序的公式2* 2^{M-1}-1的反向推导 )，来求m值
          val maxCategoriesForUnorderedFeature =
            ((math.log(maxPossibleBins / 2 + 1) / math.log(2.0)) + 1).floor.toInt
          strategy.categoricalFeaturesInfo.foreach { case (featureIndex, numCategories) =>
            // Hack: If a categorical feature has only 1 category, we treat it as continuous. 一个类别特征只有一个类别时作为连续特征。
            // TODO(SPARK-9957): Handle this properly by filtering out those features.
            if (numCategories > 1) {
              // Decide if some categorical features should be treated as unordered features,
              // 无序特征有2^(M-1)-1个split，bin时split的2倍
              //  which require 2 * ((1 << numCategories - 1) - 1) bins.
              // We do this check with log values to prevent overflows in case numCategories is large.
              // The next check is equivalent to: 2 * ((1 << numCategories - 1) - 1) <= maxBins
              // 最大分类数小于最大无序特征分类数时，无序特征增加
              if (numCategories <= maxCategoriesForUnorderedFeature) {
                unorderedFeatures.add(featureIndex)
                numBins(featureIndex) = numUnorderedBins(numCategories)
              } else {
                numBins(featureIndex) = numCategories
              }
            }
          }
        } else {
          // Binary classification or regression
          strategy.categoricalFeaturesInfo.foreach { case (featureIndex, numCategories) =>
            // If a categorical feature has only 1 category, we treat it as continuous: SPARK-9957
            if (numCategories > 1) {
              numBins(featureIndex) = numCategories
            }
          }
        }
    
        // // 设置每个节点的特征数 (对随机森林而言).
        val _featureSubsetStrategy = featureSubsetStrategy match {
          case "auto" =>
            if (numTrees == 1) {
              "all"  //决策树时，使用所有特征
            } else {
              if (strategy.algo == Classification) {
                "sqrt"  //随机森林分类时，使用开平方的特征
              } else {
                "onethird"  //随机森林回归时，使用1/3的特征
              }
            }
          case _ => featureSubsetStrategy
        }
    
        val numFeaturesPerNode: Int = _featureSubsetStrategy match {
          case "all" => numFeatures
          case "sqrt" => math.sqrt(numFeatures).ceil.toInt
          case "log2" => math.max(1, (math.log(numFeatures) / math.log(2)).ceil.toInt)
          case "onethird" => (numFeatures / 3.0).ceil.toInt
          case _ =>
            Try(_featureSubsetStrategy.toInt).filter(_ > 0).toOption match {
              case Some(value) => math.min(value, numFeatures)
              case None =>
                Try(_featureSubsetStrategy.toDouble).filter(_ > 0).filter(_ <= 1.0).toOption match {
                  case Some(value) => math.ceil(value * numFeatures).toInt
                  case _ => throw new IllegalArgumentException(s"Supported values:" +
                    s" ${RandomForestParams.supportedFeatureSubsetStrategies.mkString(", ")}," +
                    s" (0.0-1.0], [1-n].")
                }
            }
        }
    
        new DecisionTreeMetadata(numFeatures, numExamples, numClasses, numBins.max,
          strategy.categoricalFeaturesInfo, unorderedFeatures.toSet, numBins,
          strategy.impurity, strategy.quantileCalculationStrategy, strategy.maxDepth,
          strategy.minInstancesPerNode, strategy.minInfoGain, numTrees, numFeaturesPerNode)
      }
    

###### 5 初始化，找到切分点及分箱信息

    protected[tree] def findSplits(
          input: RDD[LabeledPoint],
          metadata: DecisionTreeMetadata,
          seed: Long): Array[Array[Split]] = {
    
        logDebug("isMulticlass = " + metadata.isMulticlass)
    
        val numFeatures = metadata.numFeatures
    
        // Sample the input only if there are continuous features.
        val continuousFeatures = Range(0, numFeatures).filter(metadata.isContinuous)
        val sampledInput = if (continuousFeatures.nonEmpty) {
          // Calculate the number of samples for approximate quantile calculation.
          // 采样样本数量，最少有 10000 个
          val requiredSamples = math.max(metadata.maxBins * metadata.maxBins, 10000)
          // 计算采样比例
          val fraction = if (requiredSamples < metadata.numExamples) {
            requiredSamples.toDouble / metadata.numExamples
          } else {
            1.0
          }
          logDebug("fraction of data used for calculating quantiles = " + fraction)
          // 采样数据，有放回采样
          input.sample(withReplacement = false, fraction, new XORShiftRandom(seed).nextInt())
        } else {
          input.sparkContext.emptyRDD[LabeledPoint]
        }
    
        // 分裂点策略，目前 Spark 中只实现了一种策略：排序 Sort
        findSplitsBySorting(sampledInput, metadata, continuousFeatures)
      }
      
    private def findSplitsBySorting(
          input: RDD[LabeledPoint],
          metadata: DecisionTreeMetadata,
          continuousFeatures: IndexedSeq[Int]): Array[Array[Split]] = {
    
        val continuousSplits: scala.collection.Map[Int, Array[Split]] = {
          // 当连续特征少于partititons时，减少划分点计算的并行度。this prevents tasks from being spun up that will definitely do no work.
          val numPartitions = math.min(continuousFeatures.length, input.partitions.length)
    
        // (idx, samples): (第idx个特征，该特征的样本)
          input
            .flatMap(point => continuousFeatures.map(idx => (idx, point.features(idx))))
            .groupByKey(numPartitions)
            .map { case (idx, samples) =>
              val thresholds = findSplitsForContinuousFeature(samples, metadata, idx)
              val splits: Array[Split] = thresholds.map(thresh => new ContinuousSplit(idx, thresh))
              logDebug(s"featureIndex = $idx, numSplits = ${splits.length}")
              (idx, splits)
            }.collectAsMap()
        }
        val numFeatures = metadata.numFeatures
            val splits: Array[Array[Split]] = Array.tabulate(numFeatures) {
              case i if metadata.isContinuous(i) =>
                val split = continuousSplits(i)
                metadata.setNumSplits(i, split.length)
                split
        
              case i if metadata.isCategorical(i) && metadata.isUnordered(i) =>
                // Unordered features
                // 2^(maxFeatureValue - 1) - 1 combinations
                val featureArity = metadata.featureArity(i)
                Array.tabulate[Split](metadata.numSplits(i)) { splitIndex =>
                  val categories = extractMultiClassCategories(splitIndex + 1, featureArity)
                  new CategoricalSplit(i, categories.toArray, featureArity)
                }
        
              case i if metadata.isCategorical(i) =>
                // Ordered features
                //   Splits are constructed as needed during training.
                Array.empty[Split]
            }
            splits
          }


    private[tree] def findSplitsForContinuousFeature(
          featureSamples: Iterable[Double],
          metadata: DecisionTreeMetadata,
          featureIndex: Int): Array[Double] = {
        require(metadata.isContinuous(featureIndex),
          "findSplitsForContinuousFeature can only be used to find splits for a continuous feature.")
    
        val splits = {
          // 切分数是bin的数量减1，即m-1
          val numSplits = metadata.numSplits(featureIndex)
    
          // get count for each distinct value
          // （特征值，特征值出现的次数）
          val (valueCountMap, numSamples) = featureSamples.foldLeft((Map.empty[Double, Int], 0)) {
            case ((m, cnt), x) =>
              (m + ((x, m.getOrElse(x, 0) + 1)), cnt + 1)
          }
          // sort distinct values
          // 根据特征值进行排序
          val valueCounts = valueCountMap.toSeq.sortBy(_._1).toArray
    
          // if possible splits is not enough or just enough, just return all possible splits  如果特征数小于切分数，所有特征均作为切分点
          val possibleSplits = valueCounts.length
          if (possibleSplits <= numSplits) {
            valueCounts.map(_._1)
          } else {
            // stride between splits
            // 切分点之间的步长
            val stride: Double = numSamples.toDouble / (numSplits + 1)
            logDebug("stride = " + stride)
    
            // iterate `valueCount` to find splits
            val splitsBuilder = mutable.ArrayBuilder.make[Double]
            var index = 1
            // currentCount: sum of counts of values that have been visited
             // 第一个特征的出现次数
            var currentCount = valueCounts(0)._2
            // targetCount: target value for `currentCount`.
            // If `currentCount` is closest value to `targetCount`,
            // then current value is a split threshold.
            // After finding a split threshold, `targetCount` is added by stride.
            // 如果currentCount离targetCount最近，那么当前值是切分点
            var targetCount = stride
            while (index < valueCounts.length) {
              val previousCount = currentCount
              currentCount += valueCounts(index)._2
              val previousGap = math.abs(previousCount - targetCount)
              val currentGap = math.abs(currentCount - targetCount)
              // If adding count of current value to currentCount
              // makes the gap between currentCount and targetCount smaller,
              // previous value is a split threshold.
              if (previousGap < currentGap) {
                splitsBuilder += valueCounts(index - 1)._1
                targetCount += stride
              }
              index += 1
            }
            // 最后得到切分点队列，使得分箱中的数据尽量一样多。
            splitsBuilder.result()
          }
        }
    
        // TODO: Do not fail; just ignore the useless feature.
        assert(splits.length > 0,
          s"DecisionTree could not handle feature $featureIndex since it had only 1 unique value." +
            "  Please remove this feature and then try again.")
    
        splits
      }


    /**
       * Nested method to extract list of eligible categories given an index. It extracts the
       * position of ones in a binary representation of the input. If binary
       * representation of an number is 01101 (13), the output list should (3.0, 2.0,
       * 0.0). The maxFeatureValue depict the number of rightmost digits that will be tested for ones.
       */      
    private[tree] def extractMultiClassCategories(
          input: Int,
          maxFeatureValue: Int): List[Double] = {
        var categories = List[Double]()
        var j = 0
        var bitShiftedInput = input
        while (j < maxFeatureValue) {
          if (bitShiftedInput % 2 != 0) {
            // updating the list of categories.
            categories = j.toDouble :: categories
          }
          // Right shift by one
          bitShiftedInput = bitShiftedInput >> 1
          j += 1
        }
        categories
      }
      
###### 6 迭代构建随机森林


    // org.apache.spark.ml.tree.impl.RandomForest
    // 返回到第2步中
    private[tree] def selectNodesToSplit(
          nodeQueue: mutable.Queue[(Int, LearningNode)],
          maxMemoryUsage: Long,
          metadata: DecisionTreeMetadata,
          rng: Random): (Map[Int, Array[LearningNode]], Map[Int, Map[Int, NodeIndexInfo]]) = {
        // Collect some nodes to split:
        //  nodesForGroup(treeIndex) = nodes to split
        // nodesForGroup保存需要切分的节点，treeIndex --> nodes
        val mutableNodesForGroup = new mutable.HashMap[Int, mutable.ArrayBuffer[LearningNode]]()
        val mutableTreeToNodeToIndexInfo =
          new mutable.HashMap[Int, mutable.HashMap[Int, NodeIndexInfo]]()
        var memUsage: Long = 0L
        var numNodesInGroup = 0
        // If maxMemoryInMB is set very small, we want to still try to split 1 node,
        // so we allow one iteration if memUsage == 0.
        // (global) node index是树中的索引，组中节点索引的范围是[0, numNodesInGroup)
        while (nodeQueue.nonEmpty && (memUsage < maxMemoryUsage || memUsage == 0)) {
          val (treeIndex, node) = nodeQueue.head
          // Choose subset of features for node (if subsampling).
          // 选中特征子集
          val featureSubset: Option[Array[Int]] = if (metadata.subsamplingFeatures) {
            Some(SamplingUtils.reservoirSampleAndCount(Range(0,
              metadata.numFeatures).iterator, metadata.numFeaturesPerNode, rng.nextLong())._1)
          } else {
            None
          }
          // Check if enough memory remains to add this node to the group.
          // 检查是否有足够的内存
          val nodeMemUsage = RandomForest.aggregateSizeForNode(metadata, featureSubset) * 8L
          if (memUsage + nodeMemUsage <= maxMemoryUsage || memUsage == 0) {
            nodeQueue.dequeue()
            mutableNodesForGroup.getOrElseUpdate(treeIndex, new mutable.ArrayBuffer[LearningNode]()) +=
              node
            mutableTreeToNodeToIndexInfo
              .getOrElseUpdate(treeIndex, new mutable.HashMap[Int, NodeIndexInfo]())(node.id)
              = new NodeIndexInfo(numNodesInGroup, featureSubset)
          }
          numNodesInGroup += 1
          memUsage += nodeMemUsage
        }
        if (memUsage > maxMemoryUsage) {
          // If maxMemoryUsage is 0, we should still allow splitting 1 node.
          logWarning(s"Tree learning is using approximately $memUsage bytes per iteration, which" +
            s" exceeds requested limit maxMemoryUsage=$maxMemoryUsage. This allows splitting" +
            s" $numNodesInGroup nodes in this iteration.")
        }
        // Convert mutable maps to immutable ones.
        // 将可变map转换为不可变map
        val nodesForGroup: Map[Int, Array[LearningNode]] =
          mutableNodesForGroup.mapValues(_.toArray).toMap
        val treeToNodeToIndexInfo = mutableTreeToNodeToIndexInfo.mapValues(_.toMap).toMap
        (nodesForGroup, treeToNodeToIndexInfo)
      }
    
###### 7 选中最优切分
    // array of nodes to train indexed by node index in group
        val nodes = new Array[LearningNode](numNodes)
        nodesForGroup.foreach { case (treeIndex, nodesForTree) =>
          nodesForTree.foreach { node =>
            nodes(treeToNodeToIndexInfo(treeIndex)(node.id).nodeIndexInGroup) = node
          }
        }
    
        // Calculate best splits for all nodes in the group
        timer.start("chooseSplits")
    
        // In each partition, iterate all instances and compute aggregate stats for each node,每个分片中遍历所有的实例并计算每个结点的统计信息。
        // yield a (nodeIndex, nodeAggregateStats) pair for each node.得到每个结点的(nodeIndex, nodeAggregateStats)对。
        // After a `reduceByKey` operation,reduceByKey后，统计信息被shuffled到一个特定的分片（某个worker上）并结合起来。
        // stats of a node will be shuffled to a particular partition and be combined together,
        // then best splits for nodes are found there.找到最佳划分。
        // Finally, only best Splits for nodes are collected to driver to construct decision tree.结点的最佳划分被收集到driver上建立决策树。
        // 获取节点对应的特征（nodeIndex in group->[features]）
        val nodeToFeatures = getNodeToFeatures(treeToNodeToIndexInfo)
        // 每个分区上都要知道这个结点有哪些特征。
        val nodeToFeaturesBc = input.sparkContext.broadcast(nodeToFeatures)
    
        val partitionAggregates: RDD[(Int, DTStatsAggregator)] = if (nodeIdCache.nonEmpty) {
          input.zip(nodeIdCache.get.nodeIdsForInstances).mapPartitions { points =>
            // Construct a nodeStatsAggregators array to hold node aggregate stats,
            // each node will have a nodeStatsAggregator
            // 为每个结点构建一个aggregator
            val nodeStatsAggregators = Array.tabulate(numNodes) { nodeIndex =>
              // 节点对应的特征集
              val featuresForNode = nodeToFeaturesBc.value.map { nodeToFeatures =>
                nodeToFeatures(nodeIndex)
              }
              // DTStatsAggregator，其中引用了 ImpurityAggregator，给出计算不纯度 impurity 的逻辑
              
              new DTStatsAggregator(metadata, featuresForNode)
            }
    
            // iterator all instances in current partition and update aggregate stats
            // 迭代当前分区的所有对象，更新聚合统计信息，统计信息即采样数据的权重值
            points.foreach(binSeqOpWithNodeIdCache(nodeStatsAggregators, _))
    
            // transform nodeStatsAggregators array to (nodeIndex, nodeAggregateStats) pairs,
            // which can be combined with other partition using `reduceByKey`
            nodeStatsAggregators.view.zipWithIndex.map(_.swap).iterator
          }
        } else {
          input.mapPartitions { points =>
            // Construct a nodeStatsAggregators array to hold node aggregate stats,
            // each node will have a nodeStatsAggregator
            val nodeStatsAggregators = Array.tabulate(numNodes) { nodeIndex =>
            //节点对应的特征集
              val featuresForNode = nodeToFeaturesBc.value.flatMap { nodeToFeatures =>
                Some(nodeToFeatures(nodeIndex))
              }
              new DTStatsAggregator(metadata, featuresForNode)
            }
    
            // iterator all instances in current partition and update aggregate stats
            // 迭代当前分区的所有对象，更新聚合统计信息
            points.foreach(binSeqOp(nodeStatsAggregators, _))
    
            // transform nodeStatsAggregators array to (nodeIndex, nodeAggregateStats) pairs,
            // which can be combined with other partition using `reduceByKey`
            nodeStatsAggregators.view.zipWithIndex.map(_.swap).iterator
          }
        }
    
        val nodeToBestSplits = partitionAggregates.reduceByKey((a, b) => a.merge(b)).map {
          case (nodeIndex, aggStats) =>
            val featuresForNode = nodeToFeaturesBc.value.flatMap { nodeToFeatures =>
              Some(nodeToFeatures(nodeIndex))
            }
    
            // find best split for each node
            val (split: Split, stats: ImpurityStats) =
              binsToBestSplit(aggStats, splits, featuresForNode, nodes(nodeIndex))
            (nodeIndex, (split, stats))
        }.collectAsMap()
    
        timer.stop("chooseSplits")
    
        val nodeIdUpdaters = if (nodeIdCache.nonEmpty) {
          Array.fill[mutable.Map[Int, NodeIndexUpdater]](
            metadata.numTrees)(mutable.Map[Int, NodeIndexUpdater]())
        } else {
          null
        }
        // Iterate over all nodes in this group.
        nodesForGroup.foreach { case (treeIndex, nodesForTree) =>
          nodesForTree.foreach { node =>
            val nodeIndex = node.id
            val nodeInfo = treeToNodeToIndexInfo(treeIndex)(nodeIndex)
            val aggNodeIndex = nodeInfo.nodeIndexInGroup
            val (split: Split, stats: ImpurityStats) =
              nodeToBestSplits(aggNodeIndex)
            logDebug("best split = " + split)
    
            // Extract info for this node.  Create children if not leaf.
            val isLeaf =
              (stats.gain <= 0) || (LearningNode.indexToLevel(nodeIndex) == metadata.maxDepth)
            node.isLeaf = isLeaf
            node.stats = stats
            logDebug("Node = " + node)
    
            if (!isLeaf) {
              node.split = Some(split)
              val childIsLeaf = (LearningNode.indexToLevel(nodeIndex) + 1) == metadata.maxDepth
              val leftChildIsLeaf = childIsLeaf || (stats.leftImpurity == 0.0)
              val rightChildIsLeaf = childIsLeaf || (stats.rightImpurity == 0.0)
              node.leftChild = Some(LearningNode(LearningNode.leftChildIndex(nodeIndex),
                leftChildIsLeaf, ImpurityStats.getEmptyImpurityStats(stats.leftImpurityCalculator)))
              node.rightChild = Some(LearningNode(LearningNode.rightChildIndex(nodeIndex),
                rightChildIsLeaf, ImpurityStats.getEmptyImpurityStats(stats.rightImpurityCalculator)))
    
              if (nodeIdCache.nonEmpty) {
                val nodeIndexUpdater = NodeIndexUpdater(
                  split = split,
                  nodeIndex = nodeIndex)
                nodeIdUpdaters(treeIndex).put(nodeIndex, nodeIndexUpdater)
              }
    
              // enqueue left child and right child if they are not leaves
              if (!leftChildIsLeaf) {
                nodeQueue.enqueue((treeIndex, node.leftChild.get))
              }
              if (!rightChildIsLeaf) {
                nodeQueue.enqueue((treeIndex, node.rightChild.get))
              }
    
              logDebug("leftChildIndex = " + node.leftChild.get.id +
                ", impurity = " + stats.leftImpurity)
              logDebug("rightChildIndex = " + node.rightChild.get.id +
                ", impurity = " + stats.rightImpurity)
            }
          }
        }

      
     private[tree] def binsToBestSplit(
          binAggregates: DTStatsAggregator,
          splits: Array[Array[Split]],
          featuresForNode: Option[Array[Int]],
          node: LearningNode): (Split, ImpurityStats) = {
    
        // Calculate InformationGain and ImpurityStats if current node is top node
        // 如果当前节点是根节点，计算预测和不纯度
        val level = LearningNode.indexToLevel(node.id)
        var gainAndImpurityStats: ImpurityStats = if (level == 0) {
          null
        } else {
          node.stats
        }
    
        // For each (feature, split), calculate the gain, and select the best (feature, split).
        // 对各特征及切分点，计算其信息增益并从中选择最优 (feature, split)
        val (bestSplit, bestSplitStats) =
          Range(0, binAggregates.metadata.numFeaturesPerNode).map { featureIndexIdx =>
            val featureIndex = if (featuresForNode.nonEmpty) {
              featuresForNode.get.apply(featureIndexIdx)
            } else {
              featureIndexIdx
            }
            val numSplits = binAggregates.metadata.numSplits(featureIndex)
            //特征为连续值的情况
            if (binAggregates.metadata.isContinuous(featureIndex)) {
              // Cumulative sum (scanLeft) of bin statistics.
              // Afterwards, binAggregates for a bin is the sum of aggregates for
              // that bin + all preceding bins.
              val nodeFeatureOffset = binAggregates.getFeatureOffset(featureIndexIdx)
              var splitIndex = 0
              while (splitIndex < numSplits) {
                binAggregates.mergeForFeature(nodeFeatureOffset, splitIndex + 1, splitIndex)
                splitIndex += 1
              }
              // Find best split.
              val (bestFeatureSplitIndex, bestFeatureGainStats) =
                Range(0, numSplits).map { case splitIdx =>
                //计算 leftChild 及 rightChild 子节点的 impurity
                  val leftChildStats = binAggregates.getImpurityCalculator(nodeFeatureOffset, splitIdx)
                  val rightChildStats =
                    binAggregates.getImpurityCalculator(nodeFeatureOffset, numSplits)
                  rightChildStats.subtract(leftChildStats)
                  //求 impurity 的预测值，采用的是平均值计算
                  gainAndImpurityStats = calculateImpurityStats(gainAndImpurityStats,
                    leftChildStats, //求信息增益 information gain 值，用于评估切分点是否最优
                    rightChildStats, binAggregates.metadata)
                  (splitIdx, gainAndImpurityStats)
                }.maxBy(_._2.gain)
              (splits(featureIndex)(bestFeatureSplitIndex), bestFeatureGainStats)
            } else if (binAggregates.metadata.isUnordered(featureIndex)) {
              // Unordered categorical feature
              val leftChildOffset = binAggregates.getFeatureOffset(featureIndexIdx)
              val (bestFeatureSplitIndex, bestFeatureGainStats) =
                Range(0, numSplits).map { splitIndex =>
                  val leftChildStats = binAggregates.getImpurityCalculator(leftChildOffset, splitIndex)
                  val rightChildStats = binAggregates.getParentImpurityCalculator()
                    .subtract(leftChildStats)
                  gainAndImpurityStats = calculateImpurityStats(gainAndImpurityStats,
                    leftChildStats, rightChildStats, binAggregates.metadata)
                  (splitIndex, gainAndImpurityStats)
                }.maxBy(_._2.gain)
              (splits(featureIndex)(bestFeatureSplitIndex), bestFeatureGainStats)
            } else {
              // Ordered categorical feature
              val nodeFeatureOffset = binAggregates.getFeatureOffset(featureIndexIdx)
              val numCategories = binAggregates.metadata.numBins(featureIndex)
    
              /* Each bin is one category (feature value).
               * The bins are ordered based on centroidForCategories, and this ordering determines which
               * splits are considered.  (With K categories, we consider K - 1 possible splits.)
               *
               * centroidForCategories is a list: (category, centroid)
               */
              val centroidForCategories = Range(0, numCategories).map { case featureValue =>
                val categoryStats =
                  binAggregates.getImpurityCalculator(nodeFeatureOffset, featureValue)
                val centroid = if (categoryStats.count != 0) {
                  if (binAggregates.metadata.isMulticlass) {
                    // multiclass classification
                    // For categorical variables in multiclass classification,
                    // the bins are ordered by the impurity of their corresponding labels.
                    categoryStats.calculate()
                  } else if (binAggregates.metadata.isClassification) {
                    // binary classification
                    // For categorical variables in binary classification,
                    // the bins are ordered by the count of class 1.
                    categoryStats.stats(1)
                  } else {
                    // regression
                    // For categorical variables in regression and binary classification,
                    // the bins are ordered by the prediction.
                    categoryStats.predict
                  }
                } else {
                  Double.MaxValue
                }
                (featureValue, centroid)
              }
    
              logDebug("Centroids for categorical variable: " + centroidForCategories.mkString(","))
    
              // bins sorted by centroids
              val categoriesSortedByCentroid = centroidForCategories.toList.sortBy(_._2)
    
              logDebug("Sorted centroids for categorical variable = " +
                categoriesSortedByCentroid.mkString(","))
    
              // Cumulative sum (scanLeft) of bin statistics.
              // Afterwards, binAggregates for a bin is the sum of aggregates for
              // that bin + all preceding bins.
              var splitIndex = 0
              while (splitIndex < numSplits) {
                val currentCategory = categoriesSortedByCentroid(splitIndex)._1
                val nextCategory = categoriesSortedByCentroid(splitIndex + 1)._1
                binAggregates.mergeForFeature(nodeFeatureOffset, nextCategory, currentCategory)
                splitIndex += 1
              }
              // lastCategory = index of bin with total aggregates for this (node, feature)
              val lastCategory = categoriesSortedByCentroid.last._1
              // Find best split.
              val (bestFeatureSplitIndex, bestFeatureGainStats) =
                Range(0, numSplits).map { splitIndex =>
                  val featureValue = categoriesSortedByCentroid(splitIndex)._1
                  val leftChildStats =
                    binAggregates.getImpurityCalculator(nodeFeatureOffset, featureValue)
                  val rightChildStats =
                    binAggregates.getImpurityCalculator(nodeFeatureOffset, lastCategory)
                  rightChildStats.subtract(leftChildStats)
                  gainAndImpurityStats = calculateImpurityStats(gainAndImpurityStats,
                    leftChildStats, rightChildStats, binAggregates.metadata)
                  (splitIndex, gainAndImpurityStats)
                }.maxBy(_._2.gain)
              val categoriesForSplit =
                categoriesSortedByCentroid.map(_._1.toDouble).slice(0, bestFeatureSplitIndex + 1)
              val bestFeatureSplit =
                new CategoricalSplit(featureIndex, categoriesForSplit.toArray, numCategories)
              (bestFeatureSplit, bestFeatureGainStats)
            }
          }.maxBy(_._2.gain)
    
        (bestSplit, bestSplitStats)
      }


参考文献:    
[1] 机器学习.周志华   
[2] Spark随机森林算法原理、源码分析及案例实战 https://www.ibm.com/developerworks/cn/opensource/os-cn-spark-random-forest/   
[3] PLANET: Massively Parallel Learning of Tree Ensembles with MapReduce   
[4] https://github.com/endymecy/spark-ml-source-analysis   
