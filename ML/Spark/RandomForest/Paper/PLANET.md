#### PLANET: Massively Parallel Learning of Tree Ensembles with MapReduce

---
##### ABSTRACT
PLANET：定义树的学习为一系列的分布式计算，每一步都为MapReduce。    
可扩展性。
##### 1. INTRODUCTION
MapReduce：减少了很多复杂过程，如数据分片、任务调度、故障处理、内部主机通讯等。
用MapReduce解决在大数据上构建树的可扩展性问题。    
PLANET：Parallel Learner for Assembling Numerous Ensemble Trees。
##### 2. PRELIMINARIES
最小化损失。    
**Tree Models：**
非叶节点为有序/无序特征，叶节点为预测值。这里关注二分，技术也用于多分。    
**Learning Tree Models：**
给定训练数据，找到最优树是NP-难问题。大多数算法使用贪心的从顶至下方法构建树。树根上，所有训练数据都用来计算以找到树根的最佳划分，数据集根据划分分区，这个步骤重复来建立孩子结点。    
贪心学习算法中最重要的一步是找到一个结点的最佳划分，通过减少不纯度（impurity）。最流行的不纯度评估标准有Gini稀疏、熵（Entropy）和varience（误差）。PLANET使用误差来评估划分。一个结点的误差越大，该结点不纯度越高。    
**扩展性挑战：**
FindBestSplit需要扫描一个结点全部的输入数据。    
**回归树：**
输出特征是连续的。   
FindBestSplit(D)：最大化
```math
|D|\times Var(D)-(|D_L|\times Var(D_L)+(|D_R|\times Var(D_R))      [1]
```
有序域：`$X_i<v$`    
无序域：`$X_i\in \{v_1, v_2,...v_k\}$`。Breiman算法根据观察——最优划分是按照`$Y$`平均值排序的`$X_i$`的子序列。    
StoppingCriteria(D)：`$D$`中的记录数小于一个阈值。   
FindPredicition(D)：`$Y$`的平均值。   
**MapReduce：**
Map阶段和Reduce阶段。    
##### 3. EXAMPLE   
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/4DCA841967DE472F9CB8F7C7AA2FCADD/787)   
单课树的构建过程：假设100个样本，一个结点的样本少于10就停止训练。内存限制只能操作少于25个样本。   
**组件：**   
- *Controller：* 初始化、调度、控制整个树的构建过程。调度MapReduce的job。Controller维护ModelFile。   
- *ModelFile*(M)： controller用一系列的MapReduce任务构建一棵树，每个任务构建树的不同部分。在任意一点，ModelFile包含了所有已经构建的树。  

对于一个给定的M，controller决定哪些结点可以计算。如果M中有A结点和B结点，则controller可以计算C和D的划分。信息存储在两个队列中：   
- *MapReduceQueue*(MRQ)： 包含了样本太多放不下内存的结点。   
- *InMemoryQueue*(InMemQ)： 包含了样本能放下内存的结点。 

树构建过程中，controller从MRQ和InMemQ队列中取节点出来，并调度MapReduce任务来找到结点的划分判定。一旦一个任务完成，controller更新M中的结点和划分判定，然后更新MRQ和InMemQ中的可以划分的结点。每个任务的输入为一个结点集合(`$N$`)，训练数据(`$D^*$`)和当前的模型状态(M)。Controller调度2类MapReduce任务：   
- MRQ中的结点用*MR_ExpandNodes*处理，对于一个给定的结点集合N，为每个结点计算一些列较好的划分判定候选。   
- InMemQ中的结点用*MR_InMemory*处理，直接用*InMemoryBuildNode*算法构建树。
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/BF76674E6D7A4C95B70A65BBEA42CD6E/781)

**WalkThrough：**   
树开始构建时，M，MEQ，InMemQ都为空。Controller只能扩展根节点(A)。为A找到划分点需要把训练数据全部遍历一遍。因为数据集太大（100个样本）不能全部放入内存，A被放MRQ中，InMemQ仍然为空。   
初始化后，Controller从MRQ队列中取出A，调度一个MR\_ExpandNodes({A}, M, D*)任务。这个任务计算结点A的可用划分集和每个划分点相关的信息。对每个划分点，我们计算(1)划分点的质量（不纯度减少值），(2)左右分支的预测值，(3)左右分支的训练数据量。  MR\_ExpandNodes计算的结果返回给Controller，Controller为A选择最佳划分。A结点被选择的划分加入M中。   
Controller更新队列，将可以继续划分的结点B放入相应的队列（假设B有90个样本，放入MRQ中）。   
将B从队列中取出来，调度MR\_ExpandNodes({B}, M, D*)任务。扩展B的时候，我们只需要遍历A的子树右侧的部分（左侧10个样本，停止划分），但是PLANET把所有数据集传给MapReduce。后面将描述，MR\_ExpandNodes会利用M中的状态来决定B的输入。   
一旦Controller收到了B结点上的MapReduce结果，并更新M中B的划分，现在可以扩展C和D了。B的左右儿子都是45个样本（>25），结点被放入MRQ中。调度MR\_ExpandNodes扩展C和D，找到C和D的最佳划分。Controller可以宽度有限扩展树，与in_Memory算法的深度搜索不同。   
Controller收到了C和D的划分后，调度任务来扩展E，F，G和H。H有30个样本，仍然不能放到内存中，因此加到MRQ队列中。E，F，G可以放入内存中，所以被Controller放入InMemQ中。   
Controller接下来同时调度两个MapReduc任务。MR\_InMemory({E,F,G}, M, D*)在结点E，F，G处完成树的构建。MR\_ExpandNodes计算H结点的划分。一旦InMemory任务返回，以E，F，G为根节点三个子树就构建完成了。Controller用H的子结点更新MRQ和InMemQ，继续构建树。PLANET尽量最大化节点数，因此划分点可以并行计算，并可以同时调度多个MapReduce任务。

##### 4. TECHNICAL DETAILS
###### MR_ExpandNodes: 扩展单个结点
训练放不下内存的数据集，计算结点的好的划分集。   
**Map阶段**：数据集被划分到多个Mapper上。每个Mapper将M和输入结点N载入内存。把所有结点上的数据集union起来并不等于原始数据集，然而，每一个MapReduce任务遍历全部的数据集，把map作用于每一条样本上。   
每个Mapper执行的过程用伪代码2和3表示。   
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/0F64E1AF5007432DBF9111275B7B95CA/789)   
给定训练样本(x,y)，mapper先遍历一遍现有的模型M，确定这个样本是否是输入的结点的数据集的一部分。当结点的输入集确定时，下一步是评估该结点可能的划分，并选择最好的一种。 
对有序的特征，要先排序，并仔细分区，对每个mapper上的数据范围进行跟踪。PLANET再找到最佳划分和简化数据分区中做了折中。不在每个值之间作划分，而是对每个有序特征计算一个**近似等值的直方图**来确定划分点。   
开始时，每个mapper载入每个有序特征的划分点集。对每个结点`$n\in N$`和特征`$X$`，mapper维护一个table `$T_{n,X}$`存储k-v键值对。Table中的Keys是X的划分点，values是元组(agg_tup)`$\{\sum y,\sum y^2,\sum 1\}$`
对一个特定的特征X对结点n的划分点s，元组`$T_{n,X}[s]$`包含(1)该结点上特征X小于s的样本的Y之和，(2)Y的平方，(3)特征X小于s的样本数。Mappers扫描样本子集，对每个划分点计算该节点上的agg\_tup。每个mapper输出`$n,X,s$`的Keys和对应的`$T_{n,X}[s]$`，后面的reduce阶段再aggregate起来。   
对于无序特征，每个mapper计算[1]，维护表`$T_n,X$`key,agg\_tup键值对。这里的Key对应唯一的一个特征值，`$T_{n,X}[v]$`为特征X值为v的统计信息。处理了所有输入数据后，mapper输出`$n,X$`的Keys和`$\langle v,T_{n,X}[v]\rangle$`。有序和无序特征的输出键值对形式不同。有序特征的划分好坏可以独立于这个特征上的其他划分点计算，因此划分点s作为key的一部分。运行Breiman算法，一个无序特征的所有值==要根据Y的均值进行排序==【在二元分类和回归的情况下，通过根据每个值对应的label的比例对m个特征值进行排序，可以把候选的划分split减少到m-1个。比特征age，特征值为youth、middle、senior，假设youth对应的label为1的占比0.1，middle为0.7，senior为0.2，那么候选的划分为youth|senior,middle或者youth,senior|middle】，因此，v不是key的一部分。一个简单的reducer将所有的特征的所有values排序，找到最佳划分。   
除了以上的输出，每个mapper还为每个结点n维护`$agg\_tup_n$`并输出到reducers。这些元组再各自的节点上对所有该节点上的输入计算，帮助reducer计算划分好坏。   

**Reduce阶段**：聚合和计算结点上划分的好坏。每个reducer维护一个表`$S$`以结点为索引。`$S[n]$`包含结点n的最佳划分。    
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/7ED59D4C54B643BFB5EE07CD94F49266/791)    
一个reducer处理3中keys。一：结点`$n$`和所有的`$agg\_tup_n$`组成的列表`$V$`。这些agg\_tup被聚合起来得到一个`$agg\_tup_n$`，是结点`$n$`的所有输入样本的`$\{\sum y,\sum y^2,\sum 1\}$`值。Reducers根据排序处理keys，==因此先处理带有n的keys==。Reducer处理的其他类别的keys属于有序或无序特征。二：无序特征相对应的keys形如`$n,X$`，key对应的值是由无序特征值v和agg\_tup对。对于每个v，agg\_tups聚合起来得到n结点的所有输入样本上特征X的值是v的`$\{\sum y,\sum y^2,\sum 1\}$`。一旦聚合起来，使用Breiman算法来找到特征X的最优划分，如果划分结果比之前n的所有划分都好，那么更新`$S[n]$`。三：对有序特征，keys形如`$n,X,s$`，V是agg\_tups列表。把这些聚合起来成为`$agg\_tup_left$`，得到n结点的所有输入样本中特征X的值小于s的样本的`$\{\sum y,\sum y^2,\sum 1\}$`。使用`$agg\_tup_n$`和`$agg\_tup_left$`可以直接计算划分`$X<s$`的`$Var$`。如果对n结点的划分`$X<s$`比这个reducer==所知的==先前的划分都好，那么更新`$S[n]$`。    
最后，每个reducer输出每个结点都可见的最佳划分`$S[n]$`，同时也输出`$Y$`的平均值以及左右字数的样本数。Controller得到所有reducers的划分，并且得到每个结点最佳划分，更新M，更新队列。

###### MR_InMemory: 内存中构建树
构建树的过程中，很多结点的输入数据集变得足够小更放入内存。Controller在这种情况下调度MR_InMemory任务。该任务将数据集分区到mapper集上。对每个样本`$(x,y)$`遍历一遍M中的树看是否属于某个结点。如果存在这样的结点，map将输出`$n,(x,y)$`键值对。reduce接收结点n和其上的训练样本集到内存中，然后用算法1构建树。

###### Controller设计
Controller主线程（算法8）从队列中调度任务，直到队列空为止。被调度的MapReduce任务在独立的线程中，因此Controller可以并行处理多个任务。当MR_ExpandNodes任务结束，队列更新结点并可以扩展（算法6）。
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/95EB2BDA6B304DFB878A34D7CE93F10C/793)
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/6155952EA22D4AB8A02E0BF6BD34A748/795)
![image](http://note.youdao.com/yws/public/resource/1b9ef2c5d414f98f0c8bb01532772683/xmlnote/D3C2C6A95A404D119B90A486069044DF/797)   
当MR_InMemory结束在一个结点上的运行时，没有队列的更新，因为该结点上子树的构建已经完成。   
第一，controller把MRQ和InMemQ中多有的结点移出来并调度MapReduce任务，因此看起来Controller不需要维护队列其可以直接调度随后的MapReduce任务。实际上并非如此。内存限制和集群中的可用主机阻止Controller一次性调度所有结点的任务。   
第二，当调度结点的集合时，Controller不确定结点需要的输入样本集。而是简单地发送整个训练样本集到每个任务。如果某个结点集需要的输入集相当小，Controller就发送了多余地输入。另一方面，这种设计使整个系统简单。为了防止无用输入，Controller需要将每个结点的输入样本集写到存储中，会使系统复杂，比如检查点、组合树构建等。多余信息也由广度优先建树减轻。如果我们在第i+1层能在一个任务中扩展所有结点，那么每个训练样本都是扩展结点的输入的一部分。最后，MapReduce框架已经优化了数据的扫描。加入更多mapper也可以减轻多余的读取成本。
