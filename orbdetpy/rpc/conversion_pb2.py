# -*- coding: utf-8 -*-
# Generated by the protocol buffer compiler.  DO NOT EDIT!
# source: conversion.proto

import sys
_b=sys.version_info[0]<3 and (lambda x:x) or (lambda x:x.encode('latin1'))
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from google.protobuf import reflection as _reflection
from google.protobuf import symbol_database as _symbol_database
# @@protoc_insertion_point(imports)

_sym_db = _symbol_database.Default()


import orbdetpy.rpc.messages_pb2 as messages__pb2


DESCRIPTOR = _descriptor.FileDescriptor(
  name='conversion.proto',
  package='',
  syntax='proto3',
  serialized_options=_b('\n\016org.astria.rpcB\021ConversionRequestP\000'),
  serialized_pb=_b('\n\x10\x63onversion.proto\x1a\x0emessages.proto2D\n\nConversion\x12\x36\n\x0etransformFrame\x12\x14.TransformFrameInput\x1a\x0c.DoubleArray\"\x00\x42%\n\x0eorg.astria.rpcB\x11\x43onversionRequestP\x00\x62\x06proto3')
  ,
  dependencies=[messages__pb2.DESCRIPTOR,])



_sym_db.RegisterFileDescriptor(DESCRIPTOR)


DESCRIPTOR._options = None

_CONVERSION = _descriptor.ServiceDescriptor(
  name='Conversion',
  full_name='Conversion',
  file=DESCRIPTOR,
  index=0,
  serialized_options=None,
  serialized_start=36,
  serialized_end=104,
  methods=[
  _descriptor.MethodDescriptor(
    name='transformFrame',
    full_name='Conversion.transformFrame',
    index=0,
    containing_service=None,
    input_type=messages__pb2._TRANSFORMFRAMEINPUT,
    output_type=messages__pb2._DOUBLEARRAY,
    serialized_options=None,
  ),
])
_sym_db.RegisterServiceDescriptor(_CONVERSION)

DESCRIPTOR.services_by_name['Conversion'] = _CONVERSION

# @@protoc_insertion_point(module_scope)