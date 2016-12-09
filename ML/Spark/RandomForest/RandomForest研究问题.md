## 算法稳定性研究问题
### 1 算法结果的正确性
#### 节点何时不再扩展？  
- 树高度达到maxDepth；  
- minInfoGain 当前节点的所有属性分割带来的信息增益都比这个值要小；  
- minInstancesPerNod 需要保证节点分割出的左右子节点的最少的样本数量达到这个值。
#### 算法何时结束？  
队列为空。  

### 2 算法结果的确定性
#### 算法中出现随机采样的地方？  
- 在findSplits()中找到每个特征的候选切分点时对样本（行）进行有放回的随机采样。不采样的情况：numExamples<10000。
```
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
```

- convert to bagged RDD时，每个instance在每个subsampled dataset（每棵tree）中随机取的次数由泊松采样确定，无放回采样。不采样的情况：numTrees=1且strategy.subsamplingRate=1.0。

```
val poisson = new PoissonDistribution(subsample)
      poisson.reseedRandomGenerator(seed + partitionIndex + 1)
      instances.map { instance =>
        val subsampleWeights = new Array[Double](numSubsamples)
        var subsampleIndex = 0
        while (subsampleIndex < numSubsamples) {
          subsampleWeights(subsampleIndex) = poisson.sample()
          subsampleIndex += 1
        }
        new BaggedPoint(instance, subsampleWeights)
```

- 为group（从队列中拉一些节点下来，作为本次要计算的节点组）中每个结点选择待计算的特征，随机森林中每个节点选择1/3的特征。不采样的情况：numTrees=1。
```
val featureSubset: Option[Array[Int]] = if (metadata.subsamplingFeatures) {
        Some(SamplingUtils.reservoirSampleAndCount(Range(0,
          metadata.numFeatures).iterator, metadata.numFeaturesPerNode, rng.nextLong())._1)
```

#### 在同样的数据和参数下多次运行，结果是否一致？（树是否一致）  
为避免采样，要求输入数据为小于10000个样本，参数配置中numTrees=1，即为决策树。

训练样本数 | 特征数 | maxDepth | maxBins |  categoricalFeaturesInfo | impurity
---|--- | --- | --- | --- | --- 
8000 | 1000 | 5 | 32 | empty | variance

系统配置：     

partiiton个数 | 分块大小 | total-executor-cores | memory per node | cores per executor
---|--- | --- | --- | --- 
8 | 8.8MB | 8 | 3G | 2 

多次运行：

运行次数 |运行时间 | 循环次数
---|  --- | ---
1 |  12s  | 5
2 | 12s | 5  
3 | 13s | 5  

结果相同：
```
  Tree 0:
    If (feature 404 <= 0.4990869220226053)
     If (feature 787 <= 2.2640568197483275)
      If (feature 857 <= 1.0)
       If (feature 218 <= 4.7619489563323345)
        If (feature 994 <= 4.8691722614854465)
         Predict: 0.5015479876160991
        Else (feature 994 > 4.8691722614854465)
         Predict: 0.4294573643410853
       Else (feature 218 > 4.7619489563323345)
        If (feature 88 <= 0.42900904903514425)
         Predict: 0.4795180722891566
        Else (feature 88 > 0.42900904903514425)
         Predict: 0.6062846580406654
      Else (feature 857 > 1.0)
       If (feature 433 <= 1.0)
        If (feature 4 <= 0.2100183196819918)
         Predict: 0.6037735849056604
        ... ...
```



#### 改变数据顺序或一些参数后，结果是否一致？（样本顺序、分块大小、partition个数）  
- 改变样本顺序：调用Repartition，partition个数不变（app-20161207153414-0581），运行结果与原顺序一致。  
- 改变分块大小和partition个数，partition个数由8变为4（app-20161207153921-0583），运行结果与原始数据一致。


### 3 *算法结果对比*
#### 同样算法、同样数据在Spark、Scikit-learn上的结果是否有不同？
#### 不同的原因是什么？


## 应用可靠性研究问题
### 1 *异常数据生成*
- 数据量
- 数据倾斜
- 维度过高
- 分布异常
- 数据稀疏

### 2 参数组合测试  

####  参数与执行时间和资源消耗正相关或负相关？   
- 最大树深maxDepth、树的数量numTrees、maxbins：正相关。  
其中maxbins影响chooseSplits的时间，并且会增加循环次数、内存的使用以及shuffle的大小。
```
app-20161208161059-0655
maxbins = 5
  init: 3.777297742
  total: 11.197702952
  findSplits: 3.486487384
  findBestSplits: 7.065545172
  chooseSplits: 6.96172984


```

```
app-20161208161203-0656
maxbins = 1000
  init: 5.574677414
  total: 237.837386411
  findSplits: 5.212893859
  findBestSplits: 231.871627362
  chooseSplits: 231.77056638

```
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/938EEF263E084CA2AECC3B016295306C/2300)
原因：  
```
// 节点：
class LearningNode(
    var id: Int,
    var leftChild: Option[LearningNode],
    var rightChild: Option[LearningNode],
    var split: Option[Split],
    var isLeaf: Boolean,
    var stats: ImpurityStats)
```
当bin增加时，节点存储的stats增加，节点占用内存增大，每次从队列中取出的节点数就相应减小，导致循环次数增加，同时stats的增加也导致DTStatsAggregator的增大，shuffle write和read也相应增大。  
- 样本总规模不变时，partition number、input split size对时间和资源使用的影响？  
总样本规模：3,000维*40,000样本，  
partition number=8

```
app-20161208164828-0657
  init: 3.500521296
  total: 18.192094022
  findSplits: 3.155794897
  findBestSplits: 14.40085746
  chooseSplits: 14.300127574

```
partition number=4
```
app-20161208165232-0659
  init: 4.730926909
  total: 22.94088558
  findSplits: 4.443077984
  findBestSplits: 17.897088506
  chooseSplits: 17.796293195

```
在input split size小于block大小（128MB）时，运行时间与分片数负相关。

#### 如何削减组合测试空间？

## 应用扩展性研究问题
### 1 应用是否能够线性扩展？  

生成稠密数据测试扩展性：
特征数 | maxDepth | maxBins |  categoricalFeaturesInfo | impurity | numTrees
--- | --- | --- | --- | --- | ---
 1000 | 10 | 32 | empty | variance | 10

系统配置：
partiiton个数  | total-executor-cores | memory per node | cores per executor
---|--- | --- | --- | --- 
8  | 8 | 3G | 2 

应用 | 样本数 | Job2 | Job3 | 循环次数 | Job0 task
---|--- | --- | ---| --- |--- 
0614 | 10,000 | 0.1s| 3s | 10 | 8
0615 | 20,000 | 0.1s| 3s | 10 | 8 
0616 | 30,000 | 0.1s| 3s | 10 | 8  
0617 | 40,000 | 0.2s| 3s | 11 | 8  
0625 | 50,000 | 0.1s| 4s | 11 | 8  
0619 | 60,000 | 0.2s| 4s | 11 | 8 
0620 | 70,000 | 0.1s| 4s | 11 | 16 
0622 | 80,000 | 0.2s| 4s | 11 | 16 


Job2的输入和Job3的输入以及Job4的输入一致，都为前面缓存的Labeledpoint，第二次循环到最后一次循环的Jobs的输入一致，都为缓存的BaggedPoint(treePoint,Array[int])。

*init*: 初始化阶段，循环之前。  
*total*：初始化加循环。  
*findSplits*：为每个特征找到划分点和bins。  
*findBestSplit*：Choose node splits, and enqueue new nodes as needed。  
*chooseSplit*： Calculate best splits for all nodes in the group。  
```
0614:
  init: 3.903558973
  total: 13.649617136
  findSplits: 3.597851633
  findBestSplits: 9.491139299
  chooseSplits: 9.40101804
```

```
0615:
  init: 3.649160903
  total: 15.250502144
  findSplits: 3.311813926
  findBestSplits: 11.299281432
  chooseSplits: 11.207974859
```

```
0616:
  init: 3.674088853
  total: 17.999080425
  findSplits: 3.356014891
  findBestSplits: 14.022852984
  chooseSplits: 13.923256335
```


```
0617:
  init: 3.846395571
  total: 19.685390657
  findSplits: 3.526783502
  findBestSplits: 15.483312823
  chooseSplits: 15.376252502
```

```
0625:  
  init: 4.428346091   
  total: 22.304632768
  findSplits: 4.117056312
  findBestSplits: 17.539750698
  chooseSplits: 17.430958702
```

```
0619:
  init: 4.607604123
  total: 23.819418801
  findSplits: 4.039912588
  findBestSplits: 18.824139538
  chooseSplits: 18.720587941

0626:  
  init: 3.999717165
  total: 24.303710385
  findSplits: 3.430848839
  findBestSplits: 19.87639678
  chooseSplits: 19.752332695
```

```
0620:
  init: 4.327008752
  total: 29.544439897
  findSplits: 4.009201609
  findBestSplits: 24.881705097
  chooseSplits: 24.769233873

```

```
0622：
  init: 4.463834409
  total: 38.585405978
  findSplits: 4.113414142
  findBestSplits: 33.751880783
  chooseSplits: 33.636440944
```



循环部分shuffle大小与从队列中取出的node个数成正比，与node大小成正比。

![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/58CFD147F874496AB0A2B5051B1C3F58/2138)  
可以看出，在cores数量满足并行度时，在本例中，每个partition大小小于128MB时，时间为与样本数量有关的线性扩展。

### 2 如果不能达到线性扩展，那么瓶颈在哪里？  
- 内存不能足够到到放下森林中所有树同一层的节点，则将增加循环次数。考虑循环次数对线性扩展的影响。  
为了测试增加循环次数对线性扩展的影响，将树的数目增加，层数也增加。

特征数 | maxDepth | maxBins |  categoricalFeaturesInfo | impurity | numTrees
--- | --- | --- | --- | --- | ---
 1000 | 10 | 32 | empty | variance | 30

系统配置：
partiiton个数  | total-executor-cores | memory per node | cores per executor
---|--- | --- | --- | --- 
8  | 8 | 3G | 2 

应用 | 样本数 | 循环次数 | Job0 task
---|--- | --- |--- 
0627 | 10,000 | 12 | 8
0628 | 20,000 | 14 | 8 
0648 | 30,000 | 16 | 8  
0649 | 40,000 | 16 | 8  
0650 | 50,000 | 17 | 8  
0651 | 60,000 | 17 | 8 
0652 | 70,000 | 18 | 16 


```
0627：
  init: 3.750036714
  total: 27.902955081
  findSplits: 3.242866175
  findBestSplits: 23.645614292
  chooseSplits: 23.496617113

```

```
0628：
  init: 3.594964797
  total: 35.255008803
  findSplits: 3.300707657
  findBestSplits: 31.097456757
  chooseSplits: 30.727244484

```

```
0648：
  init: 3.646829927
  total: 42.776214487
  findSplits: 3.300440709
  findBestSplits: 38.502697487
  chooseSplits: 38.285340459

```

```
0649：
  init: 3.974361967
  total: 47.788130072
  findSplits: 3.603274709
  findBestSplits: 43.13708987
  chooseSplits: 42.957357602

```

```
0650：
  init: 3.625879505
  total: 53.062564461
  findSplits: 3.328961142
  findBestSplits: 48.701707986
  chooseSplits: 48.521684041

```

```
0651：
  init: 3.598728444
  total: 56.802560445
  findSplits: 3.285324368
  findBestSplits: 52.532205457
  chooseSplits: 52.14537239

```


```
0652：
  init: 4.335598722
  total: 82.176203801
  findSplits: 4.029410754
  findBestSplits: 77.132413665
  chooseSplits: 76.900560134

```
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/0D3654FA7A8C49BDB4B1BA369E369345/2260)  
可以看出循环次数对线性扩展无影响。


- 由于在findSplits中，样本数量大于10,000时会出现采样，因此考虑是否采样对线性扩展的影响，以10000作为临界值进行测试：

特征数 | maxDepth | maxBins |  categoricalFeaturesInfo | impurity | numTrees
--- | --- | --- | --- | --- | ---
 1000 | 10 | 32 | empty | variance | 10
 
应用 | 样本数 | 循环次数 | Job0 task
---|--- | --- |--- 
0630 | 2,000 | 10 | 8
0631 | 4,000 | 10 | 8 
0632 | 6,000 | 10 | 8  
0633 | 8,000 | 10 | 8  
0634 | 12,000 | 10 | 8
0635 | 14,000 | 10 | 8 
0636 | 16,000 | 10 | 8  
0639 | 18,000 | 10 | 8  


```
0630：
  init: 2.466601042
  total: 8.677475826
  findSplits: 2.186672707
  findBestSplits: 6.032026845
  chooseSplits: 5.973883337

```

```
0631：
  init: 2.88163473
  total: 9.730126553
  findSplits: 2.589088202
  findBestSplits: 6.652953967
  chooseSplits: 6.59260388

```

```
0632：
  init: 3.194168348
  total: 11.231634758
  findSplits: 2.869172385
  findBestSplits: 7.776209537
  chooseSplits: 7.693051197

```

```
0633：
  init: 3.416094196
  total: 12.887898746
  findSplits: 3.093202583
  findBestSplits: 9.207652616
  chooseSplits: 9.120400292
```


```
0634：
  init: 4.197279863
  total: 14.688987308
  findSplits: 3.894723208
  findBestSplits: 10.226808395
  chooseSplits: 10.131142118
  
0637：
  init: 3.601290169
  total: 14.812283254
  findSplits: 3.296802323
  findBestSplits: 10.921874748
  chooseSplits: 10.822136784

0645：
  init: 3.651535494
  total: 13.37182879
  findSplits: 3.342941944
  findBestSplits: 9.43992476
  chooseSplits: 9.340965204


```

```
0635：
  init: 3.591115045
  total: 14.550154694
  findSplits: 3.310738931
  findBestSplits: 10.66957828
  chooseSplits: 10.576111231

0638：
  init: 3.739951594
  total: 13.903385595
  findSplits: 3.440186672
  findBestSplits: 9.88975827
  chooseSplits: 9.792930074

```

```
0636：
  init: 3.553113348
  total: 14.984751412
  findSplits: 3.247821382
  findBestSplits: 11.148807911
  chooseSplits: 11.059859852
  
0640：
  init: 3.635931185
  total: 15.032998922
  findSplits: 3.33609289
  findBestSplits: 11.107993369
  chooseSplits: 11.015387815


```

```
0639：
  init: 3.883771893
  total: 16.363086159
  findSplits: 3.545326547
  findBestSplits: 12.173992837
  chooseSplits: 12.072389134
  
0640：
  init: 3.689864023
  total: 14.832339763
  findSplits: 3.364921115
  findBestSplits: 10.839716724
  chooseSplits: 10.74879276
  
  init: 3.669101849
  total: 16.840891753
  findSplits: 3.331749677
  findBestSplits: 12.859074054
  chooseSplits: 12.762353167



```
```
0615:
  init: 3.55103122
  total: 15.635444244
  findSplits: 3.269462496
  findBestSplits: 11.784067961
  chooseSplits: 11.690317302

```
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/5F4F244091E5442AB602B30D6DCC76B0/2235)

采样后，倾斜度减小。

## GC算法研究问题
*统计GC占用的时间（最大/最小/平均*）

## 实证分析

编号 | 问题| 描述
---|--- |---
18678 | Skewed feature subsampling in Random forest|特征选择时倾向于选择索引较大的特征
18348 | Improve tree ensemble model summary
17801 | [ML]Random Forest Regression fails for large input | maxBins设置过大

