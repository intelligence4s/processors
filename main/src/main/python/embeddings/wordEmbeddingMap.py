import numpy as np
import math
import torch.nn as nn
import torch

class WordEmbeddingMap:
    def __init__(self, config):
        self.emb_dict, self.dim, self.w2i, self.emb = load(config)

    def isOutOfVocabulary(self, word):
        return word not in self.w2i

def load(config):
    emb_dict = dict()
    w2i = {"<UNK>":0}
    i = 1
    for line in open(config.get_string("glove.matrixResourceName")):
        if not len(line.split()) == 2:
            if "\t" in line:
                delimiter = "\t"
            else:
                delimiter = " "
            word, *rest = line.rstrip().split(delimiter)
            w2i[word] = i
            i += 1
            x = np.array(list(map(float, rest)))
            vector = (x /np.linalg.norm(x))
            embedding_size = vector.shape[0]
            emb_dict[word] = vector
    base = math.sqrt(6/embedding_size)
    emb_dict["<UNK>"] = np.random.uniform(-base,base,(embedding_size))

    weights = np.zeros((len(emb_dict), embedding_size))
    for w, i in w2i.items():
        weights[i] = emb_dict[w]
    emb = nn.Embedding.from_pretrained(torch.FloatTensor(weights), freeze=True)
    return emb_dict, embedding_size, w2i, emb