// Code generated by Wire protocol buffer compiler, do not edit.
// Source: squareup.protos.kotlin.Percents in percents_in_kdoc.proto
import Foundation
import Wire

public struct Percents : Equatable {

    /**
     * e.g. "No limits, free to send and just 2.75% to receive".
     */
    public var text: String?
    public var unknownFields: Data = .init()

    public init(text: String? = nil) {
        self.text = text
    }

}

extension Percents : Proto2Codable {
    public init(from reader: ProtoReader) throws {
        var text: String? = nil

        let token = try reader.beginMessage()
        while let tag = try reader.nextTag(token: token) {
            switch tag {
            case 1: text = try reader.decode(String.self)
            default: try reader.readUnknownField(tag: tag)
            }
        }
        let unknownFields = try reader.endMessage(token: token)

        self.text = text
        self.unknownFields = unknownFields
    }

    public func encode(to writer: ProtoWriter) throws {
        try writer.encode(tag: 1, value: text)
        try writer.writeUnknownFields(unknownFields)
    }
}

extension Percents : Codable {
    public enum CodingKeys : String, CodingKey {

        case text

    }
}
