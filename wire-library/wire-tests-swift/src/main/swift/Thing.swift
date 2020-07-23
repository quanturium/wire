// Code generated by Wire protocol buffer compiler, do not edit.
// Source: com.squareup.wire.protos.kotlin.map.Thing in map.proto
import Foundation
import Wire

public struct Thing : Equatable {

    public var name: String?
    public var unknownFields: Data = .init()

    public init(name: String? = nil) {
        self.name = name
    }

}

extension Thing : Proto2Codable {
    public init(from reader: ProtoReader) throws {
        var name: String? = nil

        let token = try reader.beginMessage()
        while let tag = try reader.nextTag(token: token) {
            switch tag {
            case 1: name = try reader.decode(String.self)
            default: try reader.readUnknownField(tag: tag)
            }
        }
        let unknownFields = try reader.endMessage(token: token)

        self.name = name
        self.unknownFields = unknownFields
    }

    public func encode(to writer: ProtoWriter) throws {
        try writer.encode(tag: 1, value: name)
        try writer.writeUnknownFields(unknownFields)
    }
}

extension Thing : Codable {
    public enum CodingKeys : String, CodingKey {

        case name

    }
}
