import torch.nn as nn
import torch
from torch.autograd import Variable

import numpy as np

concatenateCount = 0

UNK_WORD = "<UNK>"
EOS_WORD = "<EOS>"

UNK_EMBEDDING = 0

START_TAG = "<START>"
STOP_TAG = "<STOP>"

RANDOM_SEED = 2522620396 # used for both DyNet, and the JVM seed for shuffling data
WEIGHT_DECAY = 1e-5

LOG_MIN_VALUE = -10000.0

DEFAULT_DROPOUT_PROBABILITY = 0.0 # no dropout by  default

IS_DYNET_INITIALIZED = False

TYPE_VITERBI = 1
TYPE_GREEDY = 2

NONLIN_NONE = 0
NONLIN_RELU = 1
NONLIN_TANH = 2

nonlin_map = {"relu":NONLIN_RELU, "tanh":NONLIN_TANH, "":NONLIN_NONE}

TYPE_GREEDY_STRING = "greedy"
TYPE_VITERBI_STRING = "viterbi"

DEFAULT_IS_DUAL = 0

def save(file, values, comment):
    file.write("# " + comment + "\n")
    for key, value in values.items():
        file.write(f"{key}\t{value}\n")
    file.write("\n")

def mkCharacterEmbedding(word, c2i, charLookupParameters, charRnnBuilder):
    hidden_dim = charRnnBuilder.hidden_size
    charEmbeddings = charLookupParameters(torch.LongTensor([c2i.get(c, UNK_EMBEDDING) for c in word]))
    _, result = transduce(charEmbeddings, charRnnBuilder)
    # Zheng: Not sure if this is the right way to concatenate the two direction hidden states
    return result.view(hidden_dim*2)

def readString2Ids(s2iFilename):
    s2i = dict()
    with open(s2iFilename) as f:
        for line in f:
            if not line.startswith("#"):
                k, v = line.strip().split('\t')
                s2i[k] = int(v)
    return s2i

def readChar2Ids(s2iFilename):
    s2i = dict()
    with open(s2iFilename) as f:
        for line in f:
            if not line.startswith("#") and line.rstrip():
                k, v = line.strip().split('\t')
                s2i[chr(int(k))] = int(v)
    return s2i

def transduce(embeddings, builder):

    builder = builder.float()

    hidden_dim = builder.hidden_size
    bi_direct = builder.bidirectional
    mode = builder.mode

    if mode == 'LSTM':
        if bi_direct:
            (h, c) =  (torch.zeros(2, 1, hidden_dim), torch.zeros(2, 1, hidden_dim)) 
            output, (result, c) = builder(embeddings.unsqueeze(1), (h, c))
        else:
            (h, c) =  (torch.zeros(1, 1, hidden_dim), torch.zeros(1, 1, hidden_dim)) 
            output, (result, c) = builder(embeddings.unsqueeze(1), (h, c))
    elif mode == 'GRU':
        if bi_direct:
            h =  torch.zeros(2, 1, hidden_dim) 
            output, result = builder(embeddings.unsqueeze(1), h)
        else:
            h =  torch.zeros(1, 1, hidden_dim)
            output, result = builder(embeddings.unsqueeze(1), h)

    return output, result

def expressionDropout(expression, dropoutProb, doDropout):
    if doDropout and dropoutProb > 0:
        dropout = nn.Dropout(dropoutProb)
        return dropout(expression)
    else:
        return expression

def sentenceLossGreedy(emissionScoresForSeq, golds):
    assert(emissionScoresForSeq.size(0) == len(golds))
    criterion = nn.CrossEntropyLoss()
    golds = Variable(torch.LongTensor(golds))
    return criterion(emissionScoresForSeq, golds)

def emissionScoresToArrays(expressions):
    return [expr.data.tolist() for expr in expressions]

def printCoNLLOutput(pw, words, golds, preds):

    assert(len(words) == len(golds))
    assert(len(words) == len(preds))

    for i in range(len(words)):
      pw.write(f"{words[i]} {golds[i]} {preds[i]}\n")
    pw.write("\n")

def log_sum_exp(vec):
    max_score = vec[0, argmax(vec)]
    max_score_broadcast = max_score.view(1, -1).expand(1, vec.size()[1])
    return max_score + \
        torch.log(torch.sum(torch.exp(vec - max_score_broadcast)))
    









