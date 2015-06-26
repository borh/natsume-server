#!/usr/bin/env R -f

library(ggplot2)
library(reshape)

# theme_set(theme_grey(base_size = 24))

#d <- read.csv('副詞リスト-2015-06-03-1-no-chisq-test.csv', header = TRUE)
d <- read.csv('副詞リスト-2015-06-03-1.csv', header = TRUE)
# d <- subset(d, 全コーパスにおける出現割合の平均 > 5.0)
d <- subset(d, 科学技術論文.出現割合 > 1.0)
d$判定 <- as.logical(d$判定)
d <- unique(d)
d$準正用の平均出現割合 <- (d$科学技術論文.出現割合 + d$白書.出現割合 + d$法律.出現割合)/3
d$準誤用の平均出現割合 <- (d$Yahoo_知恵袋.出現割合 + d$Yahoo_ブログ.出現割合 + d$国会会議録.出現割合)/3

d.m <- melt(d)

d.subset <- subset(d, orth.base=='例えば' | orth.base=='最も')

## TODO: For each lemma, find those with most different frequency profiles.

ggplot(d.m)

## TODO: Plot all or a subset of orth.bases according to a +, - target corpus axis.

#cairo_pdf("準誤用対準誤用の出現割合の平均-no-chisq-test.pdf",width=16,height=9)
cairo_pdf("準誤用対準誤用の出現割合の平均.pdf",width=16,height=9, onefile = TRUE)
theme_set(theme_grey(base_size = 12))
ggplot(d, aes(準誤用の平均出現割合, 準正用の平均出現割合)) + geom_text(aes(label=orth.base, color=判定)) + ggtitle('準誤用対準誤用の出現割合の平均') + scale_size_continuous(range = c(1, 12))
ggplot(d, aes(準誤用の平均出現割合, 準正用の平均出現割合)) + geom_text(aes(label=orth.base, color=判定)) + ggtitle('準誤用対準誤用の出現割合の平均（拡大）') + scale_size_continuous(range = c(1.5, 18)) + scale_x_continuous(limits=c(0, 200)) + scale_y_continuous(limits=c(0, 100))
dev.off()

library(Rtsne)

d.rel.freq <- d[,c(2, 22:33)]
#d.rel.freq <- d.rel.freq[complete.cases(d.rel.freq), ]
#d.rel.freq <- unique(d.rel.freq)

d.tsne <- Rtsne(as.matrix(d.rel.freq[, -1]))

d.tsne.data <- as.data.frame(d.tsne$Y)

rownames(d.tsne.data) <- d.rel.freq$orth.base
colnames(d.tsne.data) <- c('x', 'y')

ggplot(d.tsne.data, aes(x, y)) + geom_point(aes(color=d.rel.freq$科学技術論文.出現割合)) + geom_text(aes(label=d.rel.freq$orth.base, color=d.rel.freq$科学技術論文.出現割合, size=log(d.rel.freq$科学技術論文.出現割合)), hjust = 0.5, vjust = 1.5) + ggtitle('tsne') + scale_size_continuous(range = c(2, 12))
