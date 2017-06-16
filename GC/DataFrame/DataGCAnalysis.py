# coding=UTF-8
import xlrd
import  openpyxl
import xlsxwriter
import numpy as np
import matplotlib.pyplot as plt
import pandas as pd

#df = pd.read_excel("data.xls",sheetname=[1])
f=open("data.txt","r")
sourceInLines = f.readlines()
f.close
new = []
for line in sourceInLines:

    temp1 = line.strip('\n')
    temp2 = temp1.split('\t')
    new.append(list(map(int, line.split('\t'))))

#for i in range(0,9):
 #  for j in range(0,5):
  #     new[i][j]=new[i][j]



#把四个list导入到pandas的数据结构中，dataframe



x_text=["PS-1-7","CMS-1-7","G1-1-7","PS-2-14","CMS-2-14","G1-2-14","PS-4-28","CMS-4-28","G1-4-28"]
x_text=["PS-1-7","PS-1-7","PS-1-7","CMS-1-7","CMS-1-7","CMS-1-7","G1-1-7","G1-1-7","G1-1-7",
        "PS-2-14","PS-2-14","PS-2-14","CMS-2-14","CMS-2-14","CMS-2-14","G1-2-14","G1-2-14","G1-2-14",
        "PS-4-28","PS-4-28","PS-4-28","CMS-4-28","CMS-4-28","CMS-4-28","G1-4-28","G1-4-28","G1-4-28"]

#x=range(len(new))
#y_text=[new[0],new[1],new[2],new[3],new[4],new[5],new[6],new[7],new[8]]
#y=range(len(new))
#plt.yticks(y,y_text)
plt.boxplot(new, labels=x_text,sym='',whis=10000)
#plt.xticks(x, x_text)
#data.boxplot()
#plt.show()
plt.xticks(rotation=315)
plt.xticks(size=7)
#data.boxplot()
plt.ylabel("Reduce Task Garbage Collection Time(ms)")
plt.xlabel("GC-Cores-Memory")#我们设置横纵坐标的标题。
plt.title("SVM with 11.13GB Data")
plt.title("PageRank with 12.19GB Data")
plt.title("GroupByTest")
plt.show()